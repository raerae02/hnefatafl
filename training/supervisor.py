from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "training" / "output"
LOCK = OUTPUT / "supervisor.lock"
PID_FILE = OUTPUT / "supervisor.pid"
STATE = OUTPUT / "state.json"
LOG = OUTPUT / "logs" / "supervisor.log"


class ProcessLock:
    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.file = path.open("a+b")
        self.file.seek(0, os.SEEK_END)
        if self.file.tell() == 0:
            self.file.write(b"0")
            self.file.flush()
        self.file.seek(0)

    def acquire(self) -> None:
        if os.name == "nt":
            import msvcrt

            try:
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as error:
                raise RuntimeError(
                    "A training supervisor is already running."
                ) from error
        else:
            import fcntl

            try:
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as error:
                raise RuntimeError(
                    "A training supervisor is already running."
                ) from error

    def close(self) -> None:
        self.file.close()


def read_status() -> str:
    if not STATE.is_file():
        return "unknown"
    try:
        return str(json.loads(STATE.read_text(encoding="utf-8")).get("status"))
    except (OSError, json.JSONDecodeError):
        return "unknown"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--days", type=float, default=3.0)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()

    OUTPUT.mkdir(parents=True, exist_ok=True)
    LOG.parent.mkdir(parents=True, exist_ok=True)
    lock = ProcessLock(LOCK)
    try:
        lock.acquire()
        PID_FILE.write_text(str(os.getpid()), encoding="ascii")
        delays = (5, 15, 60, 300, 300)
        failures = 0
        while True:
            command = [
                sys.executable,
                str(ROOT / "training" / "pipeline.py"),
                "run",
                "--days",
                str(args.days),
            ]
            if args.smoke:
                command.append("--smoke")
            with LOG.open("a", encoding="utf-8") as log:
                log.write(f"\n[{time.ctime()}] launching pipeline\n")
                log.flush()
                result = subprocess.run(
                    command,
                    cwd=ROOT,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                )

            status = read_status()
            if result.returncode == 0 or status in ("complete", "stopped"):
                return 0
            failures += 1
            if failures >= len(delays):
                with LOG.open("a", encoding="utf-8") as log:
                    log.write(
                        f"[{time.ctime()}] giving up after {failures} crashes; "
                        "run train.cmd resume after inspecting logs\n"
                    )
                return result.returncode or 1
            time.sleep(delays[failures - 1])
    finally:
        try:
            PID_FILE.unlink()
        except FileNotFoundError:
            pass
        lock.close()


if __name__ == "__main__":
    raise SystemExit(main())
