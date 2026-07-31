from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "training" / "output"
CONTROL = OUTPUT / "control.properties"
STATE = OUTPUT / "state.json"


def read_properties(path: Path = CONTROL) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        values[name.strip()] = value.strip()
    return values


def write_properties(values: dict[str, str], path: Path = CONTROL) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".partial")
    content = "".join(f"{name}={values[name]}\n" for name in sorted(values))
    with temporary.open("w", encoding="utf-8") as target:
        target.write(content)
        target.flush()
        os.fsync(target.fileno())
    os.replace(temporary, path)


def read_state() -> dict[str, object]:
    if not STATE.is_file():
        return {}
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def write_state(state: dict[str, object]) -> None:
    STATE.parent.mkdir(parents=True, exist_ok=True)
    temporary = STATE.with_suffix(".json.partial")
    with temporary.open("w", encoding="utf-8") as target:
        target.write(json.dumps(state, indent=2, sort_keys=True) + "\n")
        target.flush()
        os.fsync(target.fileno())
    os.replace(temporary, STATE)


def parse_duration(value: str) -> float:
    match = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*([mhd])\s*", value.lower())
    if not match:
        raise ValueError("Duration must look like 30m, 6h, or 1d.")
    amount = float(match.group(1))
    unit = {"m": 60.0, "h": 3600.0, "d": 86400.0}[match.group(2)]
    return amount * unit


def format_remaining(seconds: float) -> str:
    if seconds <= 0:
        return "deadline reached"
    days, rest = divmod(int(seconds), 86400)
    hours, rest = divmod(rest, 3600)
    minutes = rest // 60
    return f"{days}d {hours:02d}h {minutes:02d}m"


def status() -> int:
    state = read_state()
    control = read_properties()
    if not state:
        print("Training has not been started.")
        print(f"Control: {control or 'not created'}")
        return 0

    deadline = float(state.get("deadline_epoch", 0.0) or 0.0)
    heartbeat = float(state.get("heartbeat_epoch", 0.0) or 0.0)
    summary = {
        "status": state.get("status", "unknown"),
        "stage": state.get("stage", "unknown"),
        "generation": state.get("generation", 0),
        "games": state.get("games_completed", 0),
        "positions": state.get("positions", 0),
        "effective_mode": state.get("effective_mode", "unknown"),
        "requested_mode": control.get("mode", "auto"),
        "desired": control.get("desired", "running"),
        "deadline": datetime.fromtimestamp(deadline, tz=timezone.utc)
        .astimezone()
        .isoformat(timespec="seconds")
        if deadline
        else "unknown",
        "remaining": format_remaining(deadline - time.time())
        if deadline
        else "unknown",
        "heartbeat_age_seconds": round(time.time() - heartbeat, 1)
        if heartbeat
        else None,
        "champion": state.get("champion_model") or "heuristic",
        "last_error": state.get("last_error"),
    }
    for name, value in summary.items():
        print(f"{name}: {value}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("status")
    desired_parser = subparsers.add_parser("desired")
    desired_parser.add_argument("value", choices=("running", "paused", "stopped"))
    mode_parser = subparsers.add_parser("mode")
    mode_parser.add_argument("value", choices=("study", "balanced", "max", "auto"))
    extend_parser = subparsers.add_parser("extend")
    extend_parser.add_argument("duration")
    subparsers.add_parser("finalize")
    subparsers.add_parser("initialize")
    args = parser.parse_args()

    if args.command == "status":
        return status()

    OUTPUT.mkdir(parents=True, exist_ok=True)
    control = read_properties()
    control.setdefault("desired", "running")
    control.setdefault("mode", "auto")
    control.setdefault("finalize", "false")

    if args.command == "desired":
        control["desired"] = args.value
    elif args.command == "mode":
        control["mode"] = args.value
    elif args.command == "finalize":
        control["desired"] = "running"
        control["finalize"] = "true"
    elif args.command == "initialize":
        control["desired"] = "running"
        control["mode"] = "auto"
        control["finalize"] = "false"
    elif args.command == "extend":
        seconds = parse_duration(args.duration)
        state = read_state()
        if not state:
            print("Training has not been started.", file=sys.stderr)
            return 2
        state["deadline_epoch"] = float(state["deadline_epoch"]) + seconds
        state["finalize_epoch"] = float(state["finalize_epoch"]) + seconds
        state["updated_at"] = datetime.now(timezone.utc).isoformat()
        write_state(state)
        control["finalize"] = "false"

    write_properties(control)
    print(f"Control updated: {args.command}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
