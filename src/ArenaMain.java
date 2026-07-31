import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class ArenaMain {
    private static final double NULL_RATE = 0.50;
    private static final double TARGET_RATE = 0.55;
    private static final double ERROR_RATE = 0.05;
    private static final double UPPER_LLR =
            Math.log((1.0 - ERROR_RATE) / ERROR_RATE);
    private static final double LOWER_LLR =
            Math.log(ERROR_RATE / (1.0 - ERROR_RATE));

    private ArenaMain() {}

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        PositionEvaluator candidate = NnueEvaluator.load(options.candidate);
        PositionEvaluator champion = options.champion == null
                ? HeuristicEvaluator.INSTANCE
                : NnueEvaluator.load(options.champion);
        System.out.println("Candidate: " + candidate.description());
        System.out.println("Champion: " + champion.description());

        AtomicInteger nextPair = new AtomicInteger();
        AtomicBoolean decided = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(options.workers);

        try (ArenaJournal journal = new ArenaJournal(options.checkpoint)) {
            ArenaJournal.Snapshot initial = journal.snapshot();
            Decision initialDecision = decide(initial, options);
            if (initialDecision.terminal) decided.set(true);

            for (int worker = 0; worker < options.workers; worker++) {
                executor.submit(() -> {
                    while (!decided.get() && failure.get() == null
                            && !TrainingControl.shouldStop(options.control)) {
                        int pairIndex = nextPair.getAndIncrement();
                        if (pairIndex * 2 >= options.maxGames) return;
                        if (journal.isComplete(pairIndex)) continue;
                        try {
                            Opening opening = createOpening(
                                    mixSeed(options.seed, pairIndex), options);
                            ArenaJournal.GameScore first = play(
                                    opening, candidate, champion,
                                    Board.RED, options);
                            ArenaJournal.GameScore second = play(
                                    opening, champion, candidate,
                                    Board.BLACK, options);
                            journal.append(pairIndex, first, second);
                            ArenaJournal.Snapshot snapshot = journal.snapshot();
                            Decision decision = decide(snapshot, options);
                            System.out.printf(Locale.ROOT,
                                    "PROGRESS games=%d wins=%d losses=%d draws=%d "
                                            + "score=%.4f llr=%.4f%n",
                                    snapshot.games, snapshot.wins, snapshot.losses,
                                    snapshot.draws,
                                    snapshot.points / Math.max(1, snapshot.games),
                                    decision.llr);
                            if (decision.terminal) decided.set(true);
                        } catch (ArenaPaused ignored) {
                            return;
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                            decided.set(true);
                        }
                    }
                });
            }

            executor.shutdown();
            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                if (failure.get() != null || decided.get()
                        || TrainingControl.shouldStop(options.control)) {
                    executor.shutdownNow();
                }
            }

            Throwable error = failure.get();
            if (error != null) {
                if (error instanceof Exception exception) throw exception;
                throw new IllegalStateException("Arena failed.", error);
            }

            ArenaJournal.Snapshot snapshot = journal.snapshot();
            Decision decision = TrainingControl.shouldStop(options.control)
                    ? Decision.paused(snapshot)
                    : decideAtEnd(snapshot, options);
            double rate = snapshot.points / Math.max(1, snapshot.games);
            System.out.printf(Locale.ROOT,
                    "RESULT_JSON {\"status\":\"%s\",\"promoted\":%s,"
                            + "\"games\":%d,\"wins\":%d,\"losses\":%d,"
                            + "\"draws\":%d,\"score_rate\":%.6f,"
                            + "\"llr\":%.6f}%n",
                    decision.status, decision.promoted, snapshot.games,
                    snapshot.wins, snapshot.losses, snapshot.draws,
                    rate, decision.llr);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Opening createOpening(long seed, Options options) {
        Board board = TrainingPosition.serverOpening();
        int side = Board.RED;
        SplittableRandom random = new SplittableRandom(seed);
        Map<String, Integer> history = new HashMap<>();
        history.put(historyKey(board, side), 1);

        for (int ply = 0; ply < options.openingPlies && !board.isTerminal(); ply++) {
            SearchContext context = SearchContext.create(
                    side, SearchLimits.arena(50), HeuristicEvaluator.INSTANCE,
                    new TranspositionTable(14), null);
            SearchResult result = board.searchBestMove(side, null, context);
            List<ScoredMove> ranked = result.rankedMoves;
            Move move;
            if (ranked.isEmpty()) {
                List<Move> legal = board.getLegalMoves(side);
                if (legal.isEmpty()) break;
                move = legal.get(0);
            } else {
                int choices = Math.min(4, ranked.size());
                move = ranked.get(random.nextInt(choices)).move;
            }
            board.applyMove(move);
            side = Board.opposingSide(side);
            history.merge(historyKey(board, side), 1, Integer::sum);
        }
        return new Opening(board, side, history);
    }

    private static ArenaJournal.GameScore play(
            Opening opening, PositionEvaluator redEvaluator,
            PositionEvaluator blackEvaluator, int candidateSide,
            Options options) {
        Board board = opening.board.copy();
        int side = opening.sideToMove;
        Map<String, Integer> history = new HashMap<>(opening.history);
        TranspositionTable redTable = new TranspositionTable(16);
        TranspositionTable blackTable = new TranspositionTable(16);
        int winner = board.getWinner();

        for (int ply = options.openingPlies;
             winner == 0 && ply < options.maxPlies; ply++) {
            if (TrainingControl.shouldStop(options.control)) {
                throw new ArenaPaused();
            }
            List<Move> legal = board.getLegalMoves(side);
            if (legal.isEmpty()) {
                winner = Board.opposingSide(side);
                break;
            }
            PositionEvaluator evaluator =
                    side == Board.RED ? redEvaluator : blackEvaluator;
            TranspositionTable table =
                    side == Board.RED ? redTable : blackTable;
            SearchContext context = SearchContext.create(
                    side, SearchLimits.arena(options.moveMs), evaluator,
                    table, null);
            SearchResult result = board.searchBestMove(side, null, context);
            Move move = result.bestMove;
            if (move == null || !board.isValidMove(move)) move = legal.get(0);
            board.applyMove(move);
            winner = board.getWinner();
            side = Board.opposingSide(side);
            if (history.merge(historyKey(board, side), 1, Integer::sum) >= 3) {
                winner = 0;
                break;
            }
        }

        if (winner == 0) return ArenaJournal.GameScore.DRAW;
        return winner == candidateSide
                ? ArenaJournal.GameScore.WIN
                : ArenaJournal.GameScore.LOSS;
    }

    private static Decision decide(
            ArenaJournal.Snapshot snapshot, Options options) {
        double llr = logLikelihood(snapshot);
        if (snapshot.games < options.minGames) {
            return new Decision(false, false, "running", llr);
        }
        if (llr >= UPPER_LLR) {
            return new Decision(true, true, "accepted", llr);
        }
        if (llr <= LOWER_LLR) {
            return new Decision(true, false, "rejected", llr);
        }
        if (snapshot.games >= options.maxGames) {
            return decideAtEnd(snapshot, options);
        }
        return new Decision(false, false, "running", llr);
    }

    private static Decision decideAtEnd(
            ArenaJournal.Snapshot snapshot, Options options) {
        if (snapshot.games == 0) return Decision.paused(snapshot);
        double rate = snapshot.points / snapshot.games;
        double lowerWilson = wilsonLowerBound(rate, snapshot.games);
        boolean promoted = snapshot.games >= options.minGames
                && rate >= TARGET_RATE && lowerWilson > NULL_RATE;
        return new Decision(true, promoted,
                promoted ? "accepted" : "rejected",
                logLikelihood(snapshot));
    }

    private static double logLikelihood(ArenaJournal.Snapshot snapshot) {
        return snapshot.points * Math.log(TARGET_RATE / NULL_RATE)
                + (snapshot.games - snapshot.points)
                * Math.log((1.0 - TARGET_RATE) / (1.0 - NULL_RATE));
    }

    private static double wilsonLowerBound(double rate, int games) {
        if (games == 0) return 0.0;
        double z = 1.959963984540054;
        double denominator = 1.0 + z * z / games;
        double centre = rate + z * z / (2.0 * games);
        double margin = z * Math.sqrt(
                rate * (1.0 - rate) / games
                        + z * z / (4.0 * games * games));
        return (centre - margin) / denominator;
    }

    private static String historyKey(Board board, int side) {
        return board.positionKey() + ':' + side;
    }

    private static long mixSeed(long seed, int index) {
        long value = seed + 0x9E3779B97F4A7C15L * (index + 1L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static final class Opening {
        final Board board;
        final int sideToMove;
        final Map<String, Integer> history;

        Opening(Board board, int sideToMove, Map<String, Integer> history) {
            this.board = board;
            this.sideToMove = sideToMove;
            this.history = history;
        }
    }

    private static final class ArenaPaused extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Decision {
        final boolean terminal;
        final boolean promoted;
        final String status;
        final double llr;

        Decision(boolean terminal, boolean promoted, String status, double llr) {
            this.terminal = terminal;
            this.promoted = promoted;
            this.status = status;
            this.llr = llr;
        }

        static Decision paused(ArenaJournal.Snapshot snapshot) {
            return new Decision(true, false, "paused",
                    logLikelihood(snapshot));
        }
    }

    private static final class Options {
        Path candidate;
        Path champion;
        Path checkpoint = Path.of(
                "training", "output", "arena", "current.csv");
        Path control;
        int minGames = 100;
        int maxGames = 400;
        int workers = 3;
        int moveMs = 200;
        int maxPlies = 300;
        int openingPlies = 4;
        long seed = 20260731L;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                String value = index + 1 < args.length ? args[index + 1] : null;
                switch (argument) {
                    case "--candidate" -> {
                        options.candidate = Path.of(require(argument, value));
                        index++;
                    }
                    case "--champion" -> {
                        options.champion = Path.of(require(argument, value));
                        index++;
                    }
                    case "--checkpoint" -> {
                        options.checkpoint = Path.of(require(argument, value));
                        index++;
                    }
                    case "--control" -> {
                        options.control = Path.of(require(argument, value));
                        index++;
                    }
                    case "--min-games" -> {
                        options.minGames = positive(argument, value);
                        index++;
                    }
                    case "--max-games" -> {
                        options.maxGames = positive(argument, value);
                        index++;
                    }
                    case "--workers" -> {
                        options.workers = positive(argument, value);
                        index++;
                    }
                    case "--move-ms" -> {
                        options.moveMs = positive(argument, value);
                        index++;
                    }
                    case "--max-plies" -> {
                        options.maxPlies = positive(argument, value);
                        index++;
                    }
                    case "--opening-plies" -> {
                        options.openingPlies = positive(argument, value);
                        index++;
                    }
                    case "--seed" -> {
                        options.seed = Long.parseLong(require(argument, value));
                        index++;
                    }
                    default -> throw new IllegalArgumentException(
                            "Unknown arena argument: " + argument);
                }
            }
            if (options.candidate == null) {
                throw new IllegalArgumentException("--candidate is required.");
            }
            options.minGames += options.minGames % 2;
            options.maxGames -= options.maxGames % 2;
            if (options.maxGames < options.minGames
                    || options.moveMs < 50 || options.moveMs > 300) {
                throw new IllegalArgumentException("Invalid arena limits.");
            }
            return options;
        }

        private static int positive(String name, String value) {
            int parsed = Integer.parseInt(require(name, value));
            if (parsed <= 0) throw new IllegalArgumentException(
                    name + " must be positive.");
            return parsed;
        }

        private static String require(String name, String value) {
            if (value == null) throw new IllegalArgumentException(
                    name + " requires a value.");
            return value;
        }
    }
}
