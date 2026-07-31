# Hnefatafl alpha-beta + ML

This repository contains the 13×13 Hnefatafl client, its rule/search
verification suite, and an autonomous self-play training pipeline. The learned
HNN1 evaluator runs directly in Java and is combined with iterative-deepening
alpha-beta, transposition tables, tactical quiescence, parallel root search,
and pondering.

## Three-day training on Windows

Requirements are bootstrapped automatically. The NVIDIA display driver must
already be functional and at least 20 GB of disk space should be free.

Open a terminal in the project folder and run:

```bat
train.cmd start --days 3
```

The command installs per-user Python 3.12 and Java 21 when needed, creates an
isolated PyTorch CUDA environment, verifies the engine and RTX GPU, starts a
detached supervisor, prevents system sleep while active, and registers
per-user restart-at-login recovery.

The automatic local-time schedule is:

| Time | Mode | Self-play workers | GPU duty target |
|---|---:|---:|---:|
| 08:00–18:00 | `study` | 3 | 55% |
| 18:00–23:00 | `balanced` | 4 | 80% |
| 23:00–08:00 | `max` | 6 | 98% |

Manual controls take effect at a safe search/checkpoint boundary:

```bat
train.cmd status
train.cmd pause
train.cmd resume
train.cmd mode study
train.cmd mode balanced
train.cmd mode max
train.cmd mode auto
train.cmd extend 6h
train.cmd finalize
train.cmd logs
train.cmd stop
```

`mode auto` returns control to the schedule. `pause` releases the training
load but does not move the wall-clock deadline. `stop` removes restart-at-login
while preserving replay data and checkpoints. `finalize` immediately starts
the final train/arena/package phase.

At completion, the ready-to-copy client is:

```text
dist/hnefatafl-trained.zip
```

It contains its own minimal Java runtime, so the playing computer does not
need Python, PyTorch, CUDA, or a separately installed JDK.

## Training design

- Exact server opening: 24 attackers, 12 defenders, and the king.
- Strict 50–300 ms alpha-beta searches for self-play; the real client retains
  its four-second search budget.
- Crash-recoverable, checksummed HTD1 game shards.
- A 128-unit king-relative NNUE-style value network and factorized root-policy
  head.
- Candidate models are promoted only by paired, color-swapped SPRT arenas.
- Atomic model/optimizer/state checkpoints and a rolling ten-million-position
  replay window.
- The final six hours of a normal run are reserved for validation and
  packaging.

## Build and verification

With Java 21:

```sh
mkdir -p build/classes
javac -Xlint:all -d build/classes src/*.java
java -cp build/classes Main
java -cp build/classes TrainingVerification
```

On the Windows training computer, the full dependency and CUDA check is:

```bat
train.cmd doctor
```

A short end-to-end pipeline check is available as:

```bat
train.cmd smoke
```

## Playing

The unpacked trained archive starts with `run-client.cmd`. For a source build:

```sh
java -cp build/classes Client --model models/champion.hnn --time-ms 4000
```

The default server is `stank-backbone.tun.ply.gg:1415`. If the model is absent,
corrupt, or incompatible, the client reports the problem and safely falls back
to its handcrafted evaluator.
