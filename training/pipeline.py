from __future__ import annotations

import argparse
import json
import logging
from logging.handlers import RotatingFileHandler
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

import torch

from control import (
    OUTPUT,
    read_properties,
    read_state,
    write_properties,
    write_state,
)
from hnefatafl_ml import inspect_htd_files


ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = ROOT / "training" / "config.json"
REPLAY = OUTPUT / "replay"
MODELS = OUTPUT / "models"
CHECKPOINTS = OUTPUT / "checkpoints"
ARENA = OUTPUT / "arena"
LOGS = OUTPUT / "logs"
BUILD = ROOT / "build"
CLASSES = BUILD / "classes"
JAR = BUILD / "hnefatafl.jar"
CHAMPION = ROOT / "models" / "champion.hnn"
WORKER_CONTROL = OUTPUT / "worker-control.properties"
PROGRESS_RE = re.compile(r"games=(\d+)\s+samples=(\d+)")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def load_config(smoke: bool) -> dict[str, object]:
    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    if smoke:
        config.update(
            {
                "bootstrap_games": 2,
                "games_per_cycle": 2,
                "games_per_shard": 2,
                "min_move_ms": 50,
                "max_move_ms": 50,
                "max_plies": 12,
                "training_epochs": 1,
                "training_batch_size": 16,
                "arena_move_ms": 50,
                "arena_min_games": 2,
                "arena_max_games": 2,
                "arena_opening_plies": 1,
                "finalization_reserve_hours": 0,
            }
        )
        for profile in config["profiles"].values():
            profile["workers"] = min(2, int(profile["workers"]))
            profile["loader_workers"] = 0
            profile["gpu_duty"] = 1.0
    return config


def configure_logging() -> logging.Logger:
    LOGS.mkdir(parents=True, exist_ok=True)
    logger = logging.getLogger("hnefatafl-training")
    logger.setLevel(logging.INFO)
    if logger.handlers:
        return logger
    formatter = logging.Formatter(
        "%(asctime)s %(levelname)s %(message)s", "%Y-%m-%d %H:%M:%S"
    )
    file_handler = RotatingFileHandler(
        LOGS / "pipeline.log",
        maxBytes=10 * 1024 * 1024,
        backupCount=5,
        encoding="utf-8",
    )
    file_handler.setFormatter(formatter)
    logger.addHandler(file_handler)
    stream_handler = logging.StreamHandler()
    stream_handler.setFormatter(formatter)
    logger.addHandler(stream_handler)
    return logger


LOGGER = configure_logging()


def initialize_state(days: float, config: dict[str, object]) -> dict[str, object]:
    state = read_state()
    if state and state.get("status") not in ("complete", "stopped", "failed"):
        return state

    first_generation = int(state.get("generation", 0)) + 1 if state else 1
    now = time.time()
    deadline = now + days * 86400.0
    reserve = float(config["finalization_reserve_hours"]) * 3600.0
    state = {
        "schema": 1,
        "run_id": str(uuid.uuid4()),
        "status": "starting",
        "stage": "selfplay",
        "started_at": utc_now(),
        "started_epoch": now,
        "deadline_epoch": deadline,
        "finalize_epoch": max(now, deadline - reserve),
        "generation": first_generation,
        "games_completed": 0,
        "positions": 0,
        "stage_games_done": 0,
        "stage_games_target": int(config["bootstrap_games"]),
        "champion_model": str(CHAMPION) if CHAMPION.is_file() else None,
        "learner_model": None,
        "candidate_model": None,
        "finalizing": False,
        "effective_mode": "study",
        "heartbeat_epoch": now,
        "updated_at": utc_now(),
        "last_error": None,
    }
    write_state(state)
    control = read_properties()
    control.setdefault("desired", "running")
    control.setdefault("mode", "auto")
    control.setdefault("finalize", "false")
    write_properties(control)
    return state


def save_state(state: dict[str, object], **updates: object) -> None:
    state.update(updates)
    state["heartbeat_epoch"] = time.time()
    state["updated_at"] = utc_now()
    write_state(state)


def effective_mode(control: dict[str, str]) -> str:
    requested = control.get("mode", "auto")
    if requested in ("study", "balanced", "max"):
        return requested
    hour = datetime.now().hour
    if 8 <= hour < 18:
        return "study"
    if 18 <= hour < 23:
        return "balanced"
    return "max"


def set_worker_control(desired: str) -> None:
    write_properties({"desired": desired}, WORKER_CONTROL)


def prevent_sleep(active: bool) -> None:
    if os.name != "nt":
        return
    import ctypes

    es_continuous = 0x80000000
    es_system_required = 0x00000001
    es_awaymode_required = 0x00000040
    flags = es_continuous
    if active:
        flags |= es_system_required | es_awaymode_required
    ctypes.windll.kernel32.SetThreadExecutionState(flags)


def compile_engine() -> None:
    CLASSES.mkdir(parents=True, exist_ok=True)
    sources = sorted(str(path) for path in (ROOT / "src").glob("*.java"))
    subprocess.run(
        ["javac", "-Xlint:all", "-d", str(CLASSES), *sources],
        cwd=ROOT,
        check=True,
    )
    subprocess.run(
        [
            "jar",
            "--create",
            "--file",
            str(JAR),
            "--main-class",
            "Client",
            "-C",
            str(CLASSES),
            ".",
        ],
        cwd=ROOT,
        check=True,
    )


def doctor(smoke: bool) -> None:
    compile_engine()
    subprocess.run(
        ["java", "-cp", str(CLASSES), "Main"],
        cwd=ROOT,
        check=True,
        stdout=subprocess.DEVNULL,
    )
    if not smoke:
        free = shutil.disk_usage(ROOT).free
        if free < 20 * 1024**3:
            raise RuntimeError(
                f"At least 20 GB free disk space is required; found {free / 1024**3:.1f} GB."
            )
        if os.name == "nt":
            subprocess.run(["nvidia-smi"], check=True, stdout=subprocess.DEVNULL)
        if not torch.cuda.is_available():
            raise RuntimeError(
                "PyTorch cannot access CUDA. Verify the NVIDIA driver and CUDA wheel."
            )
        device_name = torch.cuda.get_device_name(0)
        LOGGER.info("CUDA device: %s", device_name)
    else:
        LOGGER.info(
            "Smoke mode device: %s",
            torch.cuda.get_device_name(0) if torch.cuda.is_available() else "CPU",
        )


def apply_priority(process: subprocess.Popen[str], priority: str) -> None:
    try:
        import psutil

        target = psutil.Process(process.pid)
        if os.name == "nt":
            value = (
                psutil.BELOW_NORMAL_PRIORITY_CLASS
                if priority == "below_normal"
                else psutil.NORMAL_PRIORITY_CLASS
            )
            target.nice(value)
        elif priority == "below_normal":
            target.nice(10)
    except Exception as error:  # Resource limiting remains best-effort.
        LOGGER.warning("Could not change process priority: %s", error)


def terminate_gracefully(process: subprocess.Popen[str], timeout: float = 10.0) -> None:
    set_worker_control("paused")
    try:
        process.wait(timeout=timeout)
        return
    except subprocess.TimeoutExpired:
        pass
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def run_managed(
    command: list[str],
    state: dict[str, object],
    profile_name: str,
    on_line: Callable[[str], None] | None = None,
) -> tuple[str, int, list[str]]:
    set_worker_control("running")
    creationflags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    process = subprocess.Popen(
        command,
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        creationflags=creationflags,
    )
    profile = load_config(False)["profiles"].get(profile_name, {})
    apply_priority(process, str(profile.get("priority", "normal")))
    lines: list[str] = []
    output_queue: queue.Queue[str | None] = queue.Queue()

    def read_output() -> None:
        assert process.stdout is not None
        for line in process.stdout:
            output_queue.put(line.rstrip())
        output_queue.put(None)

    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()
    stop_reason = "complete"
    stop_started: float | None = None
    last_heartbeat = 0.0

    while process.poll() is None:
        try:
            while True:
                line = output_queue.get_nowait()
                if line is None:
                    break
                lines.append(line)
                LOGGER.info("[%s] %s", state.get("stage"), line)
                if on_line:
                    on_line(line)
        except queue.Empty:
            pass

        now = time.time()
        control = read_properties()
        current_mode = effective_mode(control)
        desired = control.get("desired", "running")
        if desired != "running":
            stop_reason = desired
        elif bool(state.get("finalizing")) is False and (
            control.get("finalize", "false").lower() == "true"
            or now >= float(state["finalize_epoch"])
        ):
            stop_reason = "finalize"
        elif now >= float(state["deadline_epoch"]):
            stop_reason = "deadline"
        elif current_mode != profile_name:
            stop_reason = "reconfigure"

        if stop_reason != "complete":
            if stop_started is None:
                stop_started = time.monotonic()
                set_worker_control("paused")
            elif time.monotonic() - stop_started > 10.0:
                terminate_gracefully(process, timeout=0.1)

        if now - last_heartbeat >= 5:
            save_state(state, effective_mode=current_mode)
            last_heartbeat = now
        time.sleep(0.25)

    reader.join(timeout=2)
    try:
        while True:
            line = output_queue.get_nowait()
            if line is None:
                continue
            lines.append(line)
            LOGGER.info("[%s] %s", state.get("stage"), line)
            if on_line:
                on_line(line)
    except queue.Empty:
        pass

    set_worker_control("running")
    return stop_reason, int(process.returncode or 0), lines


def extract_result_json(lines: list[str]) -> dict[str, object]:
    for line in reversed(lines):
        if line.startswith("RESULT_JSON "):
            return json.loads(line[len("RESULT_JSON ") :])
    return {}


def selfplay_command(
    state: dict[str, object],
    config: dict[str, object],
    profile: dict[str, object],
    remaining: int,
) -> list[str]:
    command = [
        "java",
        "-cp",
        str(CLASSES),
        "SelfPlayMain",
        "--games",
        str(remaining),
        "--workers",
        str(profile["workers"]),
        "--min-ms",
        str(config["min_move_ms"]),
        "--max-ms",
        str(config["max_move_ms"]),
        "--max-plies",
        str(config["max_plies"]),
        "--games-per-shard",
        str(config["games_per_shard"]),
        "--seed",
        str(
            20260731
            + int(state["generation"]) * 100_000
            + int(state["stage_games_done"])
        ),
        "--out",
        str(REPLAY),
        "--control",
        str(WORKER_CONTROL),
    ]
    champion = state.get("champion_model")
    if champion and Path(str(champion)).is_file():
        command.extend(["--model", str(champion)])
    return command


def train_command(
    state: dict[str, object],
    config: dict[str, object],
    profile: dict[str, object],
    smoke: bool,
) -> tuple[list[str], Path]:
    generation = int(state["generation"])
    candidate = MODELS / f"candidate-g{generation}.hnn"
    checkpoint = CHECKPOINTS / f"candidate-g{generation}.pt"
    command = [
        sys.executable,
        str(ROOT / "training" / "train_model.py"),
        "--replay",
        str(REPLAY),
        "--output",
        str(candidate),
        "--checkpoint",
        str(checkpoint),
        "--generation",
        str(generation),
        "--epochs",
        str(config["training_epochs"]),
        "--batch-size",
        str(config["training_batch_size"]),
        "--loader-workers",
        str(profile["loader_workers"]),
        "--gpu-duty",
        str(profile["gpu_duty"]),
        "--cpu-target",
        str(profile["cpu_target"]),
        "--memory-fraction",
        str(profile["memory_fraction"]),
        "--control",
        str(WORKER_CONTROL),
    ]
    champion = state.get("champion_model")
    if champion and Path(str(champion)).is_file():
        command.extend(["--initial-model", str(champion)])
    else:
        learner = state.get("learner_model")
        if learner and Path(str(learner)).is_file():
            command.extend(["--initial-model", str(learner)])
    if smoke:
        command.append("--smoke")
    return command, candidate


def arena_command(
    state: dict[str, object],
    config: dict[str, object],
    profile: dict[str, object],
) -> list[str]:
    generation = int(state["generation"])
    command = [
        "java",
        "-cp",
        str(CLASSES),
        "ArenaMain",
        "--candidate",
        str(state["candidate_model"]),
        "--checkpoint",
        str(ARENA / f"generation-{generation}.csv"),
        "--control",
        str(WORKER_CONTROL),
        "--min-games",
        str(config["arena_min_games"]),
        "--max-games",
        str(config["arena_max_games"]),
        "--workers",
        str(profile["workers"]),
        "--move-ms",
        str(config["arena_move_ms"]),
        "--max-plies",
        str(config["max_plies"]),
        "--opening-plies",
        str(config["arena_opening_plies"]),
        "--seed",
        str(90210 + generation * 10_000),
    ]
    champion = state.get("champion_model")
    if champion and Path(str(champion)).is_file():
        command.extend(["--champion", str(champion)])
    return command


def promote(candidate: Path) -> None:
    CHAMPION.parent.mkdir(parents=True, exist_ok=True)
    temporary = CHAMPION.with_suffix(".hnn.partial")
    shutil.copy2(candidate, temporary)
    os.replace(temporary, CHAMPION)


def prune_replay(config: dict[str, object]) -> None:
    paths = sorted(
        REPLAY.glob("*.htd"), key=lambda path: path.stat().st_mtime, reverse=True
    )
    if not paths:
        return
    max_positions = int(config["max_replay_positions"])
    max_bytes = int(config["max_output_bytes"])
    kept_positions = 0
    kept_bytes = 0
    keep: set[Path] = set()
    for path in paths:
        stats = inspect_htd_files([path])
        if keep and (
            kept_positions + stats.positions > max_positions
            or kept_bytes + stats.bytes > max_bytes
        ):
            continue
        keep.add(path)
        kept_positions += stats.positions
        kept_bytes += stats.bytes
    for path in paths:
        if path not in keep:
            path.unlink()


def build_report(state: dict[str, object], archive: Path) -> str:
    champion = state.get("champion_model") or "handcrafted heuristic"
    arena_result = state.get("last_arena") or {}
    return f"""# Hnefatafl training report

- Run: `{state.get("run_id")}`
- Started: {state.get("started_at")}
- Finished: {utc_now()}
- Wall-clock deadline: {datetime.fromtimestamp(float(state["deadline_epoch"])).astimezone().isoformat()}
- Games generated: {state.get("games_completed", 0)}
- Replay positions: {state.get("positions", 0)}
- Generations attempted: {state.get("generation", 1)}
- Selected champion: `{champion}`
- Champion checksum: `{state.get("champion_checksum") or "not applicable"}`
- Last training: `{json.dumps(state.get("last_training") or {}, sort_keys=True)}`
- Last arena: `{json.dumps(arena_result, sort_keys=True)}`
- Client archive: `{archive.name}`

Only arena-accepted learned models replace the previous champion. If no learned
model passed the gate, the packaged client deliberately uses the handcrafted
heuristic evaluator.
"""


def package_client(state: dict[str, object]) -> Path:
    compile_engine()
    dist = ROOT / "dist"
    dist.mkdir(parents=True, exist_ok=True)
    staging = OUTPUT / "package-staging"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    shutil.copy2(JAR, staging / "hnefatafl.jar")
    if CHAMPION.is_file():
        (staging / "models").mkdir()
        shutil.copy2(CHAMPION, staging / "models" / "champion.hnn")

    jlink = shutil.which("jlink")
    if not jlink:
        raise RuntimeError("jlink is required to create the standalone client.")
    subprocess.run(
        [
            jlink,
            "--add-modules",
            "java.base",
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--compress=2",
            "--output",
            str(staging / "runtime"),
        ],
        check=True,
    )
    (staging / "run-client.cmd").write_text(
        "@echo off\r\n"
        'set "ROOT=%~dp0"\r\n'
        '"%ROOT%runtime\\bin\\java.exe" -jar "%ROOT%hnefatafl.jar" '
        '--model "%ROOT%models\\champion.hnn" --time-ms 4000 %*\r\n',
        encoding="utf-8",
    )
    archive = dist / "hnefatafl-trained.zip"
    report = build_report(state, archive)
    (staging / "training-report.md").write_text(report, encoding="utf-8")
    manifest = {
        "schema": 1,
        "created_at": utc_now(),
        "run_id": state.get("run_id"),
        "champion": state.get("champion_model") or "heuristic",
        "champion_checksum": state.get("champion_checksum"),
        "games": state.get("games_completed", 0),
        "positions": state.get("positions", 0),
    }
    (staging / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    with zipfile.ZipFile(
        archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6
    ) as target:
        for path in staging.rglob("*"):
            if path.is_file():
                target.write(path, path.relative_to(staging))
    (dist / "training-report.md").write_text(report, encoding="utf-8")
    return archive


def enter_finalization(state: dict[str, object]) -> None:
    LOGGER.info("Entering final validation and packaging phase.")
    save_state(
        state,
        finalizing=True,
        stage="train",
        status="finalizing",
        stage_games_done=0,
        candidate_model=None,
    )
    control = read_properties()
    control["finalize"] = "false"
    write_properties(control)


def run_pipeline(days: float, smoke: bool) -> int:
    config = load_config(smoke)
    for directory in (REPLAY, MODELS, CHECKPOINTS, ARENA, LOGS, CHAMPION.parent):
        directory.mkdir(parents=True, exist_ok=True)
    state = initialize_state(days, config)
    try:
        doctor(smoke)
        save_state(state, status="running", last_error=None)
        prevent_sleep(True)

        while True:
            control = read_properties()
            desired = control.get("desired", "running")
            if desired == "stopped":
                save_state(state, status="stopped")
                return 0
            if desired == "paused":
                prevent_sleep(False)
                save_state(state, status="paused")
                time.sleep(1)
                continue
            prevent_sleep(True)

            now = time.time()
            if now >= float(state["deadline_epoch"]):
                LOGGER.warning(
                    "Deadline reached; packaging the last accepted champion."
                )
                save_state(state, stage="package", finalizing=True)
            elif not bool(state.get("finalizing")) and (
                control.get("finalize", "false").lower() == "true"
                or now >= float(state["finalize_epoch"])
            ):
                enter_finalization(state)

            mode = effective_mode(control)
            profile = config["profiles"][mode]
            save_state(state, effective_mode=mode, status="running")
            stage = str(state["stage"])

            if stage == "selfplay":
                target = int(state["stage_games_target"])
                done_before = int(state["stage_games_done"])
                remaining = max(0, target - done_before)
                if remaining == 0:
                    save_state(state, stage="train", status="training")
                    continue

                invocation_done = 0
                invocation_samples = 0
                base_games = int(state["games_completed"])
                base_positions = int(state["positions"])

                def selfplay_progress(line: str) -> None:
                    nonlocal invocation_done, invocation_samples
                    match = PROGRESS_RE.search(line)
                    if not match:
                        return
                    invocation_done = int(match.group(1))
                    invocation_samples = int(match.group(2))
                    save_state(
                        state,
                        stage_games_done=done_before + invocation_done,
                        games_completed=base_games + invocation_done,
                        positions=base_positions + invocation_samples,
                    )

                reason, returncode, _ = run_managed(
                    selfplay_command(state, config, profile, remaining),
                    state,
                    mode,
                    selfplay_progress,
                )
                save_state(
                    state,
                    stage_games_done=done_before + invocation_done,
                    games_completed=base_games + invocation_done,
                    positions=base_positions + invocation_samples,
                )
                if reason in (
                    "paused",
                    "stopped",
                    "reconfigure",
                    "finalize",
                    "deadline",
                ):
                    if reason == "finalize":
                        enter_finalization(state)
                    elif reason == "deadline":
                        save_state(state, stage="package", finalizing=True)
                    continue
                if returncode != 0:
                    raise RuntimeError(f"Self-play exited with code {returncode}.")
                save_state(state, stage="train", status="training")
                continue

            if stage == "train":
                command, candidate = train_command(state, config, profile, smoke)
                reason, returncode, lines = run_managed(command, state, mode)
                result = extract_result_json(lines)
                if reason in ("paused", "stopped", "reconfigure"):
                    continue
                if reason == "deadline":
                    save_state(state, stage="package", finalizing=True)
                    continue
                if returncode not in (0, 75):
                    raise RuntimeError(f"Trainer exited with code {returncode}.")
                if result.get("status") != "complete" or not candidate.is_file():
                    continue
                save_state(
                    state,
                    candidate_model=str(candidate),
                    candidate_checksum=result.get("checksum"),
                    last_training=result,
                    positions=int(result.get("positions", state["positions"])),
                    stage="arena",
                    status="arena",
                )
                continue

            if stage == "arena":
                reason, returncode, lines = run_managed(
                    arena_command(state, config, profile), state, mode
                )
                result = extract_result_json(lines)
                if reason in ("paused", "stopped", "reconfigure"):
                    continue
                if reason == "deadline":
                    save_state(state, stage="package", finalizing=True)
                    continue
                if returncode != 0:
                    raise RuntimeError(f"Arena exited with code {returncode}.")
                if result.get("status") == "paused":
                    continue
                candidate = Path(str(state["candidate_model"]))
                state["learner_model"] = str(candidate)
                if result.get("promoted") is True:
                    promote(candidate)
                    state["champion_model"] = str(CHAMPION)
                    state["champion_checksum"] = state.get("candidate_checksum")
                save_state(state, last_arena=result)
                prune_replay(config)
                if smoke or bool(state.get("finalizing")):
                    save_state(state, stage="package")
                else:
                    save_state(
                        state,
                        generation=int(state["generation"]) + 1,
                        stage="selfplay",
                        stage_games_done=0,
                        stage_games_target=int(config["games_per_cycle"]),
                        candidate_model=None,
                        status="running",
                    )
                continue

            if stage == "package":
                archive = package_client(state)
                save_state(
                    state,
                    stage="complete",
                    status="complete",
                    archive=str(archive),
                    completed_at=utc_now(),
                )
                prevent_sleep(False)
                LOGGER.info("Training complete: %s", archive)
                return 0

            if stage == "complete":
                prevent_sleep(False)
                return 0

            raise RuntimeError(f"Unknown pipeline stage: {stage}")
    except Exception as error:
        LOGGER.exception("Pipeline failed")
        save_state(state, status="crashed", last_error=str(error))
        prevent_sleep(False)
        return 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("run", "doctor", "package"))
    parser.add_argument("--days", type=float, default=3.0)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    config = load_config(args.smoke)
    if args.command == "doctor":
        doctor(args.smoke)
        print("Doctor checks passed.")
        return 0
    if args.command == "package":
        state = read_state() or initialize_state(args.days, config)
        archive = package_client(state)
        print(archive)
        return 0
    return run_pipeline(args.days, args.smoke)


if __name__ == "__main__":
    raise SystemExit(main())
