# Hnefatafl engine

This Java engine uses a segmented 169-square bitboard, incremental Zobrist
hashing, iterative-deepening PVS/alpha-beta search, a fixed transposition
table, principal-variation tracking, and a persistent database of proven
winning lines.

Its fixed RED/BLACK evaluation includes the standard material, mobility,
capture, king-safety, corner-control, and escape-corridor signals, plus two
Tafl-specific additions inspired by OpenTafl's King Distance to Victory:

- The mean and variance of all eight king → edge → corner routes. RED seeks to
  make every route long and avoids leaving one unusually easy escape.
- A two-ply forced-escape check: when BLACK can move, the evaluator checks
  whether any king move remains an immediate corner win after every legal RED
  reply. RED receives a large penalty for such a route and credit for replies
  which intercept it.

## Build and verify

```sh
mkdir -p out
javac -d out src/*.java
java -cp out EngineVerification
```

The verification suite runs the legacy rule scenarios, random grid-reference
parity checks, make/unmake and hash checks, legal principal-variation checks,
knowledge-base round-tripping, perft, and a benchmark.

## Play against the server

Start the server on `localhost:8888`, then run:

```sh
java -cp out Client
```

Optionally load only a persistent proven-line database:

```sh
java -Dhnefatafl.knowledge=winning-knowledge.bin -cp out Client
```

The client stops cleanly if the server closes the connection. Server-rejected
moves are never retried, including when a cached winning-line entry suggests
one.
