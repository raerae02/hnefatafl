from __future__ import annotations

import hashlib
import math
import os
import random
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, Sequence

import numpy as np
import torch
from torch import nn
from torch.utils.data import IterableDataset, get_worker_info


BOARD_SIZE = 13
SQUARES = BOARD_SIZE * BOARD_SIZE
HIDDEN = 128
PIECE_CODES = (4, 2, 5)  # attacker, defender, king
MAX_PIECES = 37
RELATIVE_SIZE = 25
HTD_HEADER = struct.Struct("<4sI")
HTD_FRAME = struct.Struct("<II")
HTD_GAME = struct.Struct("<bqI")
HNN_HEADER = struct.Struct("<4sIIIqI32s")


@dataclass(frozen=True)
class PositionRecord:
    board: bytes
    side: int
    chosen_move: int
    ply: int
    ranked: tuple[tuple[int, int], ...]
    outcome: int


@dataclass(frozen=True)
class DatasetStats:
    games: int
    positions: int
    bytes: int


def decode_move(encoded: int) -> tuple[int, int, int, int]:
    packed = encoded - 1
    return (
        packed & 0xF,
        (packed >> 4) & 0xF,
        (packed >> 8) & 0xF,
        (packed >> 12) & 0xF,
    )


def encode_move(fr: int, fc: int, tr: int, tc: int) -> int:
    return 1 + fr + (fc << 4) + (tr << 8) + (tc << 12)


def transform_square(square: int, symmetry: int) -> int:
    row, col = divmod(square, BOARD_SIZE)
    if symmetry >= 4:
        col = BOARD_SIZE - 1 - col
    for _ in range(symmetry % 4):
        row, col = col, BOARD_SIZE - 1 - row
    return row * BOARD_SIZE + col


def transform_move(encoded: int, symmetry: int) -> int:
    fr, fc, tr, tc = decode_move(encoded)
    start = transform_square(fr * BOARD_SIZE + fc, symmetry)
    end = transform_square(tr * BOARD_SIZE + tc, symmetry)
    return encode_move(
        start // BOARD_SIZE,
        start % BOARD_SIZE,
        end // BOARD_SIZE,
        end % BOARD_SIZE,
    )


def transform_board(board: bytes, symmetry: int) -> bytes:
    transformed = bytearray(SQUARES)
    for square, piece in enumerate(board):
        transformed[transform_square(square, symmetry)] = piece
    return bytes(transformed)


def iter_htd_games(
    path: Path, verify_crc: bool = True
) -> Iterator[tuple[int, int, list[PositionRecord]]]:
    with path.open("rb") as source:
        header = source.read(HTD_HEADER.size)
        if len(header) != HTD_HEADER.size:
            raise ValueError(f"Truncated HTD1 header: {path}")
        magic, version = HTD_HEADER.unpack(header)
        if magic != b"HTD1" or version != 1:
            raise ValueError(f"Incompatible HTD1 file: {path}")

        while True:
            frame_header = source.read(HTD_FRAME.size)
            if not frame_header:
                break
            if len(frame_header) != HTD_FRAME.size:
                raise ValueError(f"Truncated HTD1 frame header: {path}")
            length, expected_crc = HTD_FRAME.unpack(frame_header)
            if length < HTD_GAME.size or length > 16 * 1024 * 1024:
                raise ValueError(f"Invalid HTD1 frame size: {path}")
            payload = source.read(length)
            if len(payload) != length:
                raise ValueError(f"Truncated HTD1 frame: {path}")
            if verify_crc and zlib.crc32(payload) & 0xFFFFFFFF != expected_crc:
                raise ValueError(f"Invalid HTD1 CRC: {path}")

            outcome, seed, count = HTD_GAME.unpack_from(payload, 0)
            cursor = HTD_GAME.size
            records: list[PositionRecord] = []
            for _ in range(count):
                board = payload[cursor : cursor + SQUARES]
                cursor += SQUARES
                if len(board) != SQUARES:
                    raise ValueError(f"Truncated HTD1 position: {path}")
                side = payload[cursor]
                chosen, ply = struct.unpack_from("<HH", payload, cursor + 1)
                ranked_count = payload[cursor + 5]
                cursor += 6
                ranked: list[tuple[int, int]] = []
                for _ in range(ranked_count):
                    move, score = struct.unpack_from("<Hi", payload, cursor)
                    cursor += 6
                    ranked.append((move, score))
                records.append(
                    PositionRecord(
                        board=board,
                        side=side,
                        chosen_move=chosen,
                        ply=ply,
                        ranked=tuple(ranked),
                        outcome=outcome,
                    )
                )
            if cursor != len(payload):
                raise ValueError(f"Unexpected bytes in HTD1 frame: {path}")
            yield seed, outcome, records


def inspect_htd_files(paths: Sequence[Path]) -> DatasetStats:
    games = 0
    positions = 0
    total_bytes = 0
    for path in paths:
        total_bytes += path.stat().st_size
        for _, _, records in iter_htd_games(path):
            games += 1
            positions += len(records)
    return DatasetStats(games=games, positions=positions, bytes=total_bytes)


class HtdIterableDataset(IterableDataset):
    def __init__(
        self,
        paths: Sequence[Path],
        validation: bool,
        augment: bool,
        seed: int,
    ) -> None:
        super().__init__()
        self.paths = tuple(str(path) for path in paths)
        self.validation = validation
        self.augment = augment
        self.seed = seed

    def __iter__(self) -> Iterator[PositionRecord]:
        worker = get_worker_info()
        worker_id = worker.id if worker else 0
        worker_count = worker.num_workers if worker else 1
        rng = random.Random(self.seed + 1_000_003 * worker_id)
        paths = [Path(path) for path in self.paths]
        rng.shuffle(paths)
        paths = paths[worker_id::worker_count]

        for path in paths:
            games = list(iter_htd_games(path))
            rng.shuffle(games)
            for seed, _, records in games:
                is_validation = (seed & 0x7FFFFFFFFFFFFFFF) % 20 == 0
                if is_validation != self.validation:
                    continue
                rng.shuffle(records)
                for record in records:
                    if self.augment:
                        symmetry = rng.randrange(8)
                        yield PositionRecord(
                            board=transform_board(record.board, symmetry),
                            side=record.side,
                            chosen_move=transform_move(record.chosen_move, symmetry),
                            ply=record.ply,
                            ranked=tuple(
                                (transform_move(move, symmetry), score)
                                for move, score in record.ranked
                            ),
                            outcome=record.outcome,
                        )
                    else:
                        yield record


def collate_records(records: Sequence[PositionRecord]) -> dict[str, torch.Tensor]:
    batch = len(records)
    piece_types = np.zeros((batch, MAX_PIECES), dtype=np.int64)
    piece_squares = np.zeros((batch, MAX_PIECES), dtype=np.int64)
    piece_mask = np.zeros((batch, MAX_PIECES), dtype=np.float32)
    king_squares = np.full(batch, 6 * BOARD_SIZE + 6, dtype=np.int64)
    sides = np.zeros(batch, dtype=np.int64)
    counts = np.zeros((batch, 2), dtype=np.float32)
    outcomes = np.zeros(batch, dtype=np.float32)
    origin_targets = np.zeros((batch, SQUARES), dtype=np.float32)
    destination_targets = np.zeros((batch, SQUARES), dtype=np.float32)

    code_to_type = {code: index for index, code in enumerate(PIECE_CODES)}
    for row, record in enumerate(records):
        board = np.frombuffer(record.board, dtype=np.uint8)
        cursor = 0
        for square, piece_code in enumerate(board):
            piece_type = code_to_type.get(int(piece_code))
            if piece_type is None:
                continue
            if cursor >= MAX_PIECES:
                raise ValueError("More than 37 pieces in HTD1 position")
            piece_types[row, cursor] = piece_type
            piece_squares[row, cursor] = square
            piece_mask[row, cursor] = 1.0
            if piece_code == 5:
                king_squares[row] = square
            elif piece_code == 4:
                counts[row, 0] += 1.0 / 24.0
            elif piece_code == 2:
                counts[row, 1] += 1.0 / 12.0
            cursor += 1

        sides[row] = 0 if record.side == 2 else 1
        outcomes[row] = float(record.outcome)
        ranked = record.ranked or ((record.chosen_move, 0),)
        best_score = ranked[0][1]
        weights = np.asarray(
            [
                math.exp(max(-12.0, (score - best_score) / 2_000.0))
                for _, score in ranked
            ],
            dtype=np.float64,
        )
        weights /= weights.sum()
        for (move, _), weight in zip(ranked, weights, strict=True):
            fr, fc, tr, tc = decode_move(move)
            origin_targets[row, fr * BOARD_SIZE + fc] += float(weight)
            destination_targets[row, tr * BOARD_SIZE + tc] += float(weight)

    return {
        "piece_types": torch.from_numpy(piece_types),
        "piece_squares": torch.from_numpy(piece_squares),
        "piece_mask": torch.from_numpy(piece_mask),
        "king_squares": torch.from_numpy(king_squares),
        "sides": torch.from_numpy(sides),
        "counts": torch.from_numpy(counts),
        "outcomes": torch.from_numpy(outcomes),
        "origin_targets": torch.from_numpy(origin_targets),
        "destination_targets": torch.from_numpy(destination_targets),
    }


class HnefataflNnue(nn.Module):
    def __init__(self, hidden: int = HIDDEN) -> None:
        super().__init__()
        self.hidden = hidden
        self.hidden_bias = nn.Parameter(torch.zeros(hidden))
        self.absolute = nn.Embedding(3 * SQUARES, hidden)
        self.relative = nn.Embedding(3 * RELATIVE_SIZE * RELATIVE_SIZE, hidden)
        self.side = nn.Embedding(2, hidden)
        self.count_weights = nn.Parameter(torch.zeros(2, hidden))
        self.value = nn.Linear(hidden, 1)
        self.origin = nn.Linear(hidden, SQUARES)
        self.destination = nn.Linear(hidden, SQUARES)
        self.reset_parameters()

    def reset_parameters(self) -> None:
        nn.init.normal_(self.absolute.weight, std=0.02)
        nn.init.normal_(self.relative.weight, std=0.02)
        nn.init.normal_(self.side.weight, std=0.02)
        nn.init.normal_(self.hidden_bias, std=0.01)
        nn.init.normal_(self.count_weights, std=0.01)
        nn.init.xavier_uniform_(self.value.weight)
        nn.init.zeros_(self.value.bias)
        nn.init.xavier_uniform_(self.origin.weight)
        nn.init.zeros_(self.origin.bias)
        nn.init.xavier_uniform_(self.destination.weight)
        nn.init.zeros_(self.destination.bias)

    def trunk(self, batch: dict[str, torch.Tensor]) -> torch.Tensor:
        piece_types = batch["piece_types"]
        piece_squares = batch["piece_squares"]
        mask = batch["piece_mask"].unsqueeze(-1)
        king = batch["king_squares"]

        absolute_indices = piece_types * SQUARES + piece_squares
        king_row = torch.div(king, BOARD_SIZE, rounding_mode="floor").unsqueeze(1)
        king_col = (king % BOARD_SIZE).unsqueeze(1)
        piece_row = torch.div(piece_squares, BOARD_SIZE, rounding_mode="floor")
        piece_col = piece_squares % BOARD_SIZE
        relative_square = (
            (piece_row - king_row + 12) * RELATIVE_SIZE + piece_col - king_col + 12
        )
        relative_indices = piece_types * RELATIVE_SIZE * RELATIVE_SIZE + relative_square

        hidden = self.hidden_bias.unsqueeze(0)
        hidden = hidden + (self.absolute(absolute_indices) * mask).sum(dim=1)
        hidden = hidden + (self.relative(relative_indices) * mask).sum(dim=1)
        hidden = hidden + self.side(batch["sides"])
        hidden = hidden + batch["counts"] @ self.count_weights
        return torch.clamp(hidden, 0.0, 1.0)

    def forward(
        self, batch: dict[str, torch.Tensor]
    ) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        hidden = self.trunk(batch)
        value = torch.tanh(self.value(hidden).squeeze(-1))
        return value, self.origin(hidden), self.destination(hidden)


def move_batch(
    batch: dict[str, torch.Tensor], device: torch.device
) -> dict[str, torch.Tensor]:
    return {
        name: tensor.to(device, non_blocking=True) for name, tensor in batch.items()
    }


def export_hnn1(model: HnefataflNnue, path: Path, generation: int) -> str:
    model = model.cpu().eval()
    arrays: Iterable[np.ndarray] = (
        model.hidden_bias.detach().numpy(),
        model.absolute.weight.detach().numpy(),
        model.relative.weight.detach().numpy(),
        model.side.weight.detach().numpy(),
        model.count_weights.detach().numpy(),
        model.value.weight.detach().numpy().reshape(-1),
        model.value.bias.detach().numpy().reshape(-1),
        model.origin.weight.detach().numpy(),
        model.origin.bias.detach().numpy(),
        model.destination.weight.detach().numpy(),
        model.destination.bias.detach().numpy(),
    )
    payload = b"".join(
        np.asarray(array, dtype="<f4", order="C").tobytes(order="C") for array in arrays
    )
    checksum = hashlib.sha256(payload).digest()
    header = HNN_HEADER.pack(
        b"HNN1",
        1,
        BOARD_SIZE,
        model.hidden,
        generation,
        len(payload),
        checksum,
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".partial")
    with temporary.open("wb") as target:
        target.write(header + payload)
        target.flush()
        os.fsync(target.fileno())
    temporary.replace(path)
    return checksum.hex()


def load_hnn1(model: HnefataflNnue, path: Path) -> int:
    raw = path.read_bytes()
    if len(raw) < HNN_HEADER.size:
        raise ValueError(f"Truncated HNN1 model: {path}")
    magic, version, board_size, hidden, generation, length, checksum = (
        HNN_HEADER.unpack_from(raw)
    )
    payload = raw[HNN_HEADER.size :]
    if (
        magic != b"HNN1"
        or version != 1
        or board_size != BOARD_SIZE
        or hidden != model.hidden
        or length != len(payload)
        or hashlib.sha256(payload).digest() != checksum
    ):
        raise ValueError(f"Incompatible HNN1 model: {path}")

    values = np.frombuffer(payload, dtype="<f4")
    cursor = 0

    def take(shape: tuple[int, ...]) -> torch.Tensor:
        nonlocal cursor
        size = math.prod(shape)
        result = torch.from_numpy(values[cursor : cursor + size].copy()).reshape(shape)
        cursor += size
        return result

    with torch.no_grad():
        model.hidden_bias.copy_(take((hidden,)))
        model.absolute.weight.copy_(take((3 * SQUARES, hidden)))
        model.relative.weight.copy_(take((3 * RELATIVE_SIZE * RELATIVE_SIZE, hidden)))
        model.side.weight.copy_(take((2, hidden)))
        model.count_weights.copy_(take((2, hidden)))
        model.value.weight.copy_(take((1, hidden)))
        model.value.bias.copy_(take((1,)))
        model.origin.weight.copy_(take((SQUARES, hidden)))
        model.origin.bias.copy_(take((SQUARES,)))
        model.destination.weight.copy_(take((SQUARES, hidden)))
        model.destination.bias.copy_(take((SQUARES,)))
    if cursor != len(values):
        raise ValueError(f"Unexpected HNN1 payload data: {path}")
    return generation
