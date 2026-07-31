from __future__ import annotations

import argparse
import json
import os
import random
import subprocess
import time
from pathlib import Path

import numpy as np
import psutil
import torch
import torch.nn.functional as F
from torch.amp import GradScaler, autocast
from torch.utils.data import DataLoader

from hnefatafl_ml import (
    HIDDEN,
    HnefataflNnue,
    HtdIterableDataset,
    collate_records,
    export_hnn1,
    inspect_htd_files,
    load_hnn1,
    move_batch,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train and export an HNN1 model")
    parser.add_argument("--replay", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--initial-model", type=Path)
    parser.add_argument("--control", type=Path)
    parser.add_argument("--generation", type=int, required=True)
    parser.add_argument("--epochs", type=int, default=12)
    parser.add_argument("--batch-size", type=int, default=4096)
    parser.add_argument("--loader-workers", type=int, default=2)
    parser.add_argument("--gpu-duty", type=float, default=0.8)
    parser.add_argument("--cpu-target", type=float, default=0.7)
    parser.add_argument("--memory-fraction", type=float, default=0.75)
    parser.add_argument("--seed", type=int, default=20260731)
    parser.add_argument("--smoke", action="store_true")
    return parser.parse_args()


def desired_state(control: Path | None) -> str:
    if control is None or not control.exists():
        return "running"
    try:
        for line in control.read_text(encoding="utf-8").splitlines():
            if line.startswith("desired="):
                return line.split("=", 1)[1].strip().lower()
    except OSError:
        pass
    return "running"


def atomic_torch_save(value: object, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".partial")
    torch.save(value, temporary)
    with temporary.open("rb") as saved:
        os.fsync(saved.fileno())
    os.replace(temporary, path)


def save_checkpoint(
    path: Path,
    model: HnefataflNnue,
    optimizer: torch.optim.Optimizer,
    scheduler: torch.optim.lr_scheduler.LRScheduler,
    scaler: GradScaler,
    epoch: int,
    epoch_step: int,
    step: int,
    generation: int,
    best_loss: float,
) -> None:
    atomic_torch_save(
        {
            "schema": 1,
            "generation": generation,
            "epoch": epoch,
            "epoch_step": epoch_step,
            "step": step,
            "best_loss": best_loss,
            "model": model.state_dict(),
            "optimizer": optimizer.state_dict(),
            "scheduler": scheduler.state_dict(),
            "scaler": scaler.state_dict(),
            "python_rng": random.getstate(),
            "numpy_rng": np.random.get_state(),
            "torch_rng": torch.get_rng_state(),
            "cuda_rng": torch.cuda.get_rng_state_all()
            if torch.cuda.is_available()
            else [],
        },
        path,
    )


def evaluate(
    model: HnefataflNnue,
    loader: DataLoader,
    device: torch.device,
    max_batches: int,
) -> float:
    model.eval()
    total = 0.0
    batches = 0
    with torch.no_grad():
        for batch in loader:
            batch = move_batch(batch, device)
            with autocast(device_type=device.type, enabled=device.type == "cuda"):
                value, origin, destination = model(batch)
                value_loss = F.mse_loss(value, batch["outcomes"])
                origin_loss = (
                    -(batch["origin_targets"] * F.log_softmax(origin, dim=1))
                    .sum(dim=1)
                    .mean()
                )
                destination_loss = (
                    -(batch["destination_targets"] * F.log_softmax(destination, dim=1))
                    .sum(dim=1)
                    .mean()
                )
                loss = value_loss + 0.35 * (origin_loss + destination_loss)
            total += float(loss)
            batches += 1
            if batches >= max_batches:
                break
    model.train()
    return total / batches if batches else float("nan")


def train_once(args: argparse.Namespace, batch_size: int) -> dict[str, object]:
    replay_paths = sorted(args.replay.glob("*.htd"))
    if not replay_paths:
        raise RuntimeError(f"No HTD1 replay shards found in {args.replay}")
    stats = inspect_htd_files(replay_paths)
    if stats.positions == 0:
        raise RuntimeError("Replay dataset contains no positions")

    random.seed(args.seed)
    np.random.seed(args.seed & 0xFFFFFFFF)
    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)
        torch.cuda.set_per_process_memory_fraction(args.memory_fraction)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    model = HnefataflNnue(HIDDEN)
    if args.initial_model and args.initial_model.is_file():
        load_hnn1(model, args.initial_model)
    model.to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=2.0e-3, weight_decay=1.0e-4)
    max_steps_per_epoch = max(1, stats.positions * 19 // 20 // batch_size)
    if args.smoke:
        max_steps_per_epoch = min(max_steps_per_epoch, 2)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(
        optimizer, T_max=max(1, args.epochs * max_steps_per_epoch)
    )
    scaler = GradScaler("cuda", enabled=device.type == "cuda")

    start_epoch = 0
    resume_epoch_step = 0
    global_step = 0
    best_loss = float("inf")
    if args.checkpoint.is_file():
        checkpoint = torch.load(
            args.checkpoint, map_location=device, weights_only=False
        )
        if checkpoint.get("generation") == args.generation:
            model.load_state_dict(checkpoint["model"])
            optimizer.load_state_dict(checkpoint["optimizer"])
            if "scheduler" in checkpoint:
                scheduler.load_state_dict(checkpoint["scheduler"])
            scaler.load_state_dict(checkpoint["scaler"])
            start_epoch = int(checkpoint["epoch"])
            resume_epoch_step = int(checkpoint.get("epoch_step", 0))
            global_step = int(checkpoint["step"])
            best_loss = float(checkpoint["best_loss"])
            random.setstate(checkpoint["python_rng"])
            np.random.set_state(checkpoint["numpy_rng"])
            torch.set_rng_state(checkpoint["torch_rng"])
            if device.type == "cuda" and checkpoint.get("cuda_rng"):
                torch.cuda.set_rng_state_all(checkpoint["cuda_rng"])

    train_dataset = HtdIterableDataset(
        replay_paths, validation=False, augment=True, seed=args.seed
    )
    validation_dataset = HtdIterableDataset(
        replay_paths, validation=True, augment=False, seed=args.seed + 1
    )
    loader_options = {
        "batch_size": batch_size,
        "num_workers": args.loader_workers,
        "collate_fn": collate_records,
        "pin_memory": device.type == "cuda",
        "persistent_workers": args.loader_workers > 0,
    }
    train_loader = DataLoader(train_dataset, **loader_options)
    validation_loader = DataLoader(validation_dataset, **loader_options)

    patience = 0
    last_checkpoint_time = time.monotonic()
    last_loss = float("inf")
    feedback = ResourceFeedback(args.cpu_target, args.gpu_duty)

    for epoch in range(start_epoch, args.epochs):
        model.train()
        running_loss = 0.0
        epoch_steps = resume_epoch_step if epoch == start_epoch else 0
        for batch_index, batch in enumerate(train_loader):
            if epoch == start_epoch and batch_index < resume_epoch_step:
                continue
            if desired_state(args.control) != "running":
                save_checkpoint(
                    args.checkpoint,
                    model,
                    optimizer,
                    scheduler,
                    scaler,
                    epoch,
                    epoch_steps,
                    global_step,
                    args.generation,
                    best_loss,
                )
                return {
                    "status": "paused",
                    "generation": args.generation,
                    "step": global_step,
                }

            started = time.perf_counter()
            batch = move_batch(batch, device)
            optimizer.zero_grad(set_to_none=True)
            with autocast(device_type=device.type, enabled=device.type == "cuda"):
                value, origin, destination = model(batch)
                value_loss = F.mse_loss(value, batch["outcomes"])
                origin_loss = (
                    -(batch["origin_targets"] * F.log_softmax(origin, dim=1))
                    .sum(dim=1)
                    .mean()
                )
                destination_loss = (
                    -(batch["destination_targets"] * F.log_softmax(destination, dim=1))
                    .sum(dim=1)
                    .mean()
                )
                loss = value_loss + 0.35 * (origin_loss + destination_loss)
            scaler.scale(loss).backward()
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            scaler.step(optimizer)
            scaler.update()
            scheduler.step()

            last_loss = float(loss.detach())
            running_loss += last_loss
            epoch_steps += 1
            global_step += 1
            compute_seconds = time.perf_counter() - started
            throttle_delay = feedback.additional_delay()
            if args.gpu_duty < 0.999:
                throttle_delay += max(
                    0.0, compute_seconds * (1.0 / args.gpu_duty - 1.0)
                )
            if throttle_delay > 0.0:
                time.sleep(throttle_delay)

            now = time.monotonic()
            if global_step % 500 == 0 or now - last_checkpoint_time >= 120:
                save_checkpoint(
                    args.checkpoint,
                    model,
                    optimizer,
                    scheduler,
                    scaler,
                    epoch,
                    epoch_steps,
                    global_step,
                    args.generation,
                    best_loss,
                )
                last_checkpoint_time = now
            if epoch_steps >= max_steps_per_epoch:
                break

        validation_loss = evaluate(
            model,
            validation_loader,
            device,
            max_batches=2
            if args.smoke
            else max(1, stats.positions // 20 // batch_size),
        )
        if not math_is_finite(validation_loss):
            validation_loss = running_loss / max(1, epoch_steps)
        print(
            f"EPOCH epoch={epoch + 1} train={running_loss / max(1, epoch_steps):.6f} "
            f"validation={validation_loss:.6f} steps={global_step}",
            flush=True,
        )
        if validation_loss + 1.0e-5 < best_loss:
            best_loss = validation_loss
            patience = 0
            save_checkpoint(
                args.checkpoint,
                model,
                optimizer,
                scheduler,
                scaler,
                epoch + 1,
                0,
                global_step,
                args.generation,
                best_loss,
            )
        else:
            patience += 1
            if patience >= 2:
                break

    if args.checkpoint.is_file():
        best_checkpoint = torch.load(
            args.checkpoint, map_location=device, weights_only=False
        )
        if best_checkpoint.get("generation") == args.generation:
            model.load_state_dict(best_checkpoint["model"])
    checksum = export_hnn1(model, args.output, args.generation)
    return {
        "status": "complete",
        "generation": args.generation,
        "games": stats.games,
        "positions": stats.positions,
        "steps": global_step,
        "batch_size": batch_size,
        "device": str(device),
        "loss": last_loss,
        "best_validation_loss": best_loss,
        "checksum": checksum,
        "model": str(args.output),
    }


def math_is_finite(value: float) -> bool:
    return not (value != value or value in (float("inf"), float("-inf")))


class ResourceFeedback:
    def __init__(self, cpu_target: float, gpu_target: float) -> None:
        self.cpu_target = cpu_target
        self.gpu_target = gpu_target
        self.last_gpu_check = 0.0
        self.gpu_load = 0.0
        psutil.cpu_percent(interval=None)

    def additional_delay(self) -> float:
        delay = 0.0
        cpu_load = psutil.cpu_percent(interval=None) / 100.0
        if cpu_load > self.cpu_target:
            delay = max(delay, min(0.25, (cpu_load - self.cpu_target) * 0.5))

        now = time.monotonic()
        if now - self.last_gpu_check >= 2.0:
            self.last_gpu_check = now
            try:
                output = subprocess.check_output(
                    [
                        "nvidia-smi",
                        "--query-gpu=utilization.gpu",
                        "--format=csv,noheader,nounits",
                    ],
                    text=True,
                    timeout=2,
                    stderr=subprocess.DEVNULL,
                )
                self.gpu_load = float(output.splitlines()[0].strip()) / 100.0
            except (OSError, ValueError, subprocess.SubprocessError, IndexError):
                self.gpu_load = 0.0
        if self.gpu_load > self.gpu_target:
            delay = max(delay, min(0.25, (self.gpu_load - self.gpu_target) * 0.5))
        return delay


def main() -> int:
    args = parse_args()
    batch_size = args.batch_size
    while True:
        try:
            result = train_once(args, batch_size)
            print("RESULT_JSON " + json.dumps(result, sort_keys=True), flush=True)
            return 0 if result["status"] == "complete" else 75
        except torch.OutOfMemoryError:
            if batch_size <= 128:
                raise
            batch_size //= 2
            print(f"CUDA OOM: retrying with batch_size={batch_size}", flush=True)
            if torch.cuda.is_available():
                torch.cuda.empty_cache()


if __name__ == "__main__":
    raise SystemExit(main())
