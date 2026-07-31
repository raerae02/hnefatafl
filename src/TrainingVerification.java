import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class TrainingVerification {
    private TrainingVerification() {}

    public static void main(String[] args) throws Exception {
        verifyOpening();
        verifyMoveValidationParity();
        verifyStrictSearch();
        verifyMoveCodec();
        verifyDatasetRoundTrip();
        System.out.println("TRAINING_VERIFICATION PASS");
    }

    private static void verifyOpening() {
        Board board = TrainingPosition.serverOpening();
        int attackers = 0;
        int defenders = 0;
        int kings = 0;
        for (byte piece : board.encodedPosition()) {
            if (piece == Board.RED) attackers++;
            else if (piece == Board.BLACK) defenders++;
            else if (piece == Board.KING) kings++;
        }
        require(attackers == 24, "Opening must contain 24 attackers.");
        require(defenders == 12, "Opening must contain 12 defenders.");
        require(kings == 1, "Opening must contain one king.");
        require(board.grid[6][6] == Board.KING, "King must start on the throne.");
    }

    private static void verifyMoveValidationParity() {
        Board board = TrainingPosition.serverOpening();
        for (int side : new int[]{Board.RED, Board.BLACK}) {
            for (Move move : board.getLegalMoves(side)) {
                require(board.isValidMove(move),
                        "Generated move rejected by validator: " + move);
            }
        }

        int[][] grid = new int[Board.BOARD_SIZE][Board.BOARD_SIZE];
        grid[6][6] = Board.KING;
        grid[10][3] = Board.RED;
        grid[7][3] = Board.BLACK;
        Board blocked = new Board(grid);
        require(!blocked.isValidMove(new Move(10, 3, 5, 3)),
                "Vertical blocker must invalidate the move.");
        require(!blocked.isValidMove(new Move(-1, 0, 0, 0)),
                "Out-of-bounds move must be rejected.");
    }

    private static void verifyStrictSearch() {
        for (long budget : new long[]{50, 100, 300}) {
            Board board = TrainingPosition.serverOpening();
            SearchContext context = SearchContext.create(
                    Board.RED, SearchLimits.training(budget),
                    HeuristicEvaluator.INSTANCE,
                    new TranspositionTable(14), null);
            long started = System.nanoTime();
            SearchResult result = board.searchBestMove(Board.RED, null, context);
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            require(result.bestMove != null && board.isValidMove(result.bestMove),
                    "Strict search must return a legal fallback.");
            require(result.rankedMoves.size() <= 8,
                    "Search result must expose at most eight ranked moves.");
            require(elapsed <= budget + 250,
                    "Strict search exceeded tolerance: " + elapsed + " ms.");
        }
    }

    private static void verifyMoveCodec() {
        Move move = new Move(12, 11, 0, 1);
        require(move.equals(Move.decode(move.encode())),
                "Move codec must round-trip.");
    }

    private static void verifyDatasetRoundTrip() throws Exception {
        Path directory = Files.createTempDirectory("hnefatafl-htd1-");
        try {
            Board board = TrainingPosition.serverOpening();
            Move chosen = board.getLegalMoves(Board.RED).get(0);
            TrainingSample sample = new TrainingSample(
                    board.encodedPosition(), Board.RED, chosen, 0,
                    List.of(new ScoredMove(chosen, 123)));
            try (HtdDataset dataset = new HtdDataset(directory, 64)) {
                dataset.append(new SelfPlayGame(
                        42L, 0, new ArrayList<>(List.of(sample))));
            }

            Path shard;
            try (var paths = Files.list(directory)) {
                shard = paths.filter(path -> path.toString().endsWith(".htd"))
                        .findFirst().orElseThrow();
            }
            HtdDataset.DatasetStats stats = HtdDataset.inspect(shard);
            require(stats.games == 1 && stats.samples == 1,
                    "HTD1 round-trip counts are wrong.");

            Path interrupted = directory.resolve("interrupted.partial");
            Files.copy(shard, interrupted);
            Files.write(interrupted, new byte[]{7, 8, 9},
                    StandardOpenOption.APPEND);
            require(HtdDataset.recoverPartials(directory) == 1,
                    "One interrupted HTD1 shard should be recovered.");
            HtdDataset.DatasetStats recovered = HtdDataset.inspect(
                    directory.resolve("interrupted.htd"));
            require(recovered.games == 1 && recovered.samples == 1,
                    "HTD1 recovery lost a valid frame.");
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted((first, second) ->
                                Integer.compare(second.getNameCount(), first.getNameCount()))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                                // Temporary verification cleanup.
                            }
                        });
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
