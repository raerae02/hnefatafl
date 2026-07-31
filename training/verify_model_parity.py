from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

import numpy as np
import torch

from hnefatafl_ml import (
    BOARD_SIZE,
    HnefataflNnue,
    PositionRecord,
    collate_records,
    load_hnn1,
)


OPENING = bytes(
    [
        0,
        0,
        0,
        0,
        4,
        4,
        4,
        4,
        4,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        4,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        0,
        0,
        0,
        4,
        0,
        0,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        0,
        0,
        4,
        4,
        0,
        0,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        0,
        0,
        4,
        4,
        4,
        0,
        2,
        2,
        2,
        5,
        2,
        2,
        2,
        0,
        4,
        4,
        4,
        0,
        0,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        0,
        0,
        4,
        4,
        0,
        0,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        0,
        0,
        4,
        0,
        0,
        0,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        4,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        4,
        4,
        4,
        4,
        4,
        0,
        0,
        0,
        0,
    ]
)


def to_square(row: int, col: int) -> str:
    return f"{chr(ord('A') + col)}{BOARD_SIZE - row}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--classes", type=Path, required=True)
    args = parser.parse_args()

    model = HnefataflNnue()
    load_hnn1(model, args.model)
    model.eval()
    record = PositionRecord(
        board=OPENING,
        side=4,
        chosen_move=1,
        ply=0,
        ranked=(),
        outcome=0,
    )
    batch = collate_records([record])
    with torch.no_grad():
        value, origin, destination = model(batch)
    defender_score = int(np.rint(float(value[0]) * 80_000.0))

    board = np.frombuffer(OPENING, dtype=np.uint8)
    attacker_squares = np.flatnonzero(board == 4)
    legal_scores: list[tuple[float, str]] = []
    for start in attacker_squares:
        row, col = divmod(int(start), BOARD_SIZE)
        for row_step, col_step in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            target_row, target_col = row + row_step, col + col_step
            while (
                0 <= target_row < BOARD_SIZE
                and 0 <= target_col < BOARD_SIZE
                and board[target_row * BOARD_SIZE + target_col] == 0
            ):
                if (target_row, target_col) not in (
                    (0, 0),
                    (0, 12),
                    (12, 0),
                    (12, 12),
                    (6, 6),
                ):
                    score = float(
                        origin[0, start]
                        + destination[0, target_row * BOARD_SIZE + target_col]
                    )
                    move = f"{to_square(row, col)}-{to_square(target_row, target_col)}"
                    legal_scores.append((score, move))
                target_row += row_step
                target_col += col_step
    python_policy, python_move = max(legal_scores)

    output = subprocess.check_output(
        [
            "java",
            "-cp",
            str(args.classes),
            "ModelProbeMain",
            str(args.model),
        ],
        text=True,
    )
    java = json.loads(output)
    if abs(java["defender_score"] - defender_score) > 2:
        raise AssertionError(
            f"Value mismatch: Java={java['defender_score']} Python={defender_score}"
        )
    if java["attacker_score"] != -java["defender_score"]:
        raise AssertionError("Java perspective conversion is not zero-sum")
    if java["best_move"] != python_move:
        raise AssertionError(
            f"Policy move mismatch: Java={java['best_move']} Python={python_move}"
        )
    if abs(java["best_policy"] - python_policy) > 1.0e-4:
        raise AssertionError(
            f"Policy mismatch: Java={java['best_policy']} Python={python_policy}"
        )
    print(
        json.dumps(
            {
                "status": "pass",
                "defender_score": defender_score,
                "best_move": python_move,
                "policy": python_policy,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
