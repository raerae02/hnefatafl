import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class SelfPlayMain {
    private SelfPlayMain() {}

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        PositionEvaluator evaluator = options.modelPath == null
                ? HeuristicEvaluator.INSTANCE
                : NnueEvaluator.load(options.modelPath);

        int recovered = HtdDataset.recoverPartials(options.outputDirectory);
        if (recovered > 0) {
            System.out.println("Recovered " + recovered + " partial HTD1 shards.");
        }
        System.out.println("Self-play evaluator: " + evaluator.description());

        AtomicInteger nextGame = new AtomicInteger();
        AtomicInteger completedGames = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(options.workers);

        try (HtdDataset dataset = new HtdDataset(
                options.outputDirectory, options.gamesPerShard)) {
            for (int worker = 0; worker < options.workers; worker++) {
                executor.submit(() -> {
                    while (failure.get() == null
                            && !TrainingControl.shouldStop(options.controlPath)) {
                        int gameIndex = nextGame.getAndIncrement();
                        if (gameIndex >= options.games) return;
                        long seed = mixSeed(options.seed, gameIndex);
                        try {
                            SelfPlayGame game = playGame(seed, evaluator, options);
                            if (game == null) return;
                            dataset.append(game);
                            int done = completedGames.incrementAndGet();
                            System.out.printf(
                                    "PROGRESS games=%d samples=%d outcome=%d%n",
                                    done, dataset.totalSamples(), game.outcome);
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                            return;
                        }
                    }
                });
            }
            executor.shutdown();
            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                if (failure.get() != null
                        || TrainingControl.shouldStop(options.controlPath)) {
                    executor.shutdownNow();
                }
            }
        } finally {
            executor.shutdownNow();
        }

        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("Self-play failed.", error);
        }

        System.out.printf("RESULT games=%d requested=%d stopped=%s%n",
                completedGames.get(), options.games,
                TrainingControl.shouldStop(options.controlPath));
    }

    private static SelfPlayGame playGame(
            long seed, PositionEvaluator evaluator, Options options) {
        SplittableRandom random = new SplittableRandom(seed);
        Board board = TrainingPosition.serverOpening();
        int sideToMove = Board.RED;
        Map<String, Integer> history = new HashMap<>();
        history.put(historyKey(board, sideToMove), 1);
        List<TrainingSample> samples = new ArrayList<>();
        TranspositionTable table = new TranspositionTable(16);

        int winner = 0;
        for (int ply = 0; ply < options.maxPlies; ply++) {
            if (TrainingControl.shouldStop(options.controlPath)) return null;
            List<Move> legalMoves = board.getLegalMoves(sideToMove);
            if (legalMoves.isEmpty()) {
                winner = Board.opposingSide(sideToMove);
                break;
            }

            long budget = adaptiveBudget(
                    legalMoves.size(), options.minMoveMs,
                    options.maxMoveMs, random);
            SearchContext context = SearchContext.create(
                    sideToMove, SearchLimits.training(budget), evaluator,
                    table, null);
            SearchResult result = board.searchBestMove(
                    sideToMove, null, context);
            Move chosen = chooseMove(result, ply, random);
            if (chosen == null || !board.isValidMove(chosen)
                    || !belongsToSide(board, chosen, sideToMove)) {
                chosen = legalMoves.get(0);
            }

            samples.add(new TrainingSample(
                    board.encodedPosition(), sideToMove, chosen, ply,
                    result.rankedMoves));
            board.applyMove(chosen);
            winner = board.getWinner();
            if (winner != 0) break;

            sideToMove = Board.opposingSide(sideToMove);
            String key = historyKey(board, sideToMove);
            int repetitions = history.merge(key, 1, Integer::sum);
            if (repetitions >= 3) break;
        }

        int outcome = winner == Board.BLACK ? 1
                : winner == Board.RED ? -1 : 0;
        return new SelfPlayGame(seed, outcome, samples);
    }

    private static Move chooseMove(
            SearchResult result, int ply, SplittableRandom random) {
        if (result.bestMove == null || result.rankedMoves.isEmpty()) {
            return result.bestMove;
        }
        if (ply >= 12 || result.rankedMoves.size() == 1) {
            return result.bestMove;
        }

        int bestScore = result.rankedMoves.get(0).score;
        double total = 0.0;
        double[] weights = new double[result.rankedMoves.size()];
        for (int index = 0; index < weights.length; index++) {
            int delta = result.rankedMoves.get(index).score - bestScore;
            weights[index] = Math.exp(Math.max(-12.0, delta / 2_000.0));
            total += weights[index];
        }

        double choice = random.nextDouble(total);
        for (int index = 0; index < weights.length; index++) {
            choice -= weights[index];
            if (choice <= 0.0) return result.rankedMoves.get(index).move;
        }
        return result.bestMove;
    }

    private static long adaptiveBudget(
            int legalMoves, long minimum, long maximum,
            SplittableRandom random) {
        double branching = Math.min(1.0, legalMoves / 120.0);
        double fraction = 0.25 + 0.75 * branching;
        double jitter = 0.90 + random.nextDouble() * 0.20;
        long budget = minimum
                + Math.round((maximum - minimum) * fraction * jitter);
        return Math.max(minimum, Math.min(maximum, budget));
    }

    private static boolean belongsToSide(
            Board board, Move move, int side) {
        int piece = board.grid[move.fromRow][move.fromCol];
        return side == Board.RED
                ? piece == Board.RED
                : piece == Board.BLACK || piece == Board.KING;
    }

    private static String historyKey(Board board, int sideToMove) {
        return board.positionKey() + ':' + sideToMove;
    }

    private static long mixSeed(long seed, int gameIndex) {
        long value = seed + 0x9E3779B97F4A7C15L * (gameIndex + 1L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static final class Options {
        int games = 2_000;
        int workers = 3;
        long minMoveMs = 50;
        long maxMoveMs = 300;
        int maxPlies = 300;
        int gamesPerShard = 64;
        long seed = 0x484E454641544146L;
        Path outputDirectory = Path.of("training", "output", "replay");
        Path modelPath;
        Path controlPath;

        static Options parse(String[] args) throws IOException {
            Options options = new Options();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                String value = index + 1 < args.length ? args[index + 1] : null;
                switch (argument) {
                    case "--games" -> {
                        options.games = parsePositive(argument, value);
                        index++;
                    }
                    case "--workers" -> {
                        options.workers = parsePositive(argument, value);
                        index++;
                    }
                    case "--min-ms" -> {
                        options.minMoveMs = parsePositive(argument, value);
                        index++;
                    }
                    case "--max-ms" -> {
                        options.maxMoveMs = parsePositive(argument, value);
                        index++;
                    }
                    case "--max-plies" -> {
                        options.maxPlies = parsePositive(argument, value);
                        index++;
                    }
                    case "--games-per-shard" -> {
                        options.gamesPerShard = parsePositive(argument, value);
                        index++;
                    }
                    case "--seed" -> {
                        options.seed = Long.parseLong(requireValue(argument, value));
                        index++;
                    }
                    case "--out" -> {
                        options.outputDirectory =
                                Path.of(requireValue(argument, value));
                        index++;
                    }
                    case "--model" -> {
                        options.modelPath = Path.of(requireValue(argument, value));
                        index++;
                    }
                    case "--control" -> {
                        options.controlPath = Path.of(requireValue(argument, value));
                        index++;
                    }
                    default -> throw new IllegalArgumentException(
                            "Unknown self-play argument: " + argument);
                }
            }
            if (options.minMoveMs < 50
                    || options.maxMoveMs < options.minMoveMs
                    || options.maxMoveMs > 300) {
                throw new IllegalArgumentException(
                        "Self-play move budgets must be inside 50..300 ms.");
            }
            Files.createDirectories(options.outputDirectory);
            return options;
        }

        private static int parsePositive(String name, String value) {
            int parsed = Integer.parseInt(requireValue(name, value));
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be positive.");
            }
            return parsed;
        }

        private static String requireValue(String name, String value) {
            if (value == null) {
                throw new IllegalArgumentException(name + " requires a value.");
            }
            return value;
        }
    }
}
