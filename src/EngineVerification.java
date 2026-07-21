import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;

/** Fast automated property, perft, persistence, and search checks without external test dependencies. */
public final class EngineVerification {
    private EngineVerification() {}

    public static void main(String[] args) throws Exception {
        legacySuite();
        Board board = sampleBoard();
        rejectedRootMovesAreNeverReused();
        rejectedMovesOverrideKnowledgeBase();
        randomizedMakeUnmake(board, 2_000, 7L);
        principalVariationIsLegal(board);
        knowledgeRoundTrip(board);
        long depthOne = perft(board.copy(), Board.RED, 1);
        long depthTwo = perft(board.copy(), Board.RED, 2);
        if (depthOne <= 0 || depthTwo <= depthOne) throw new AssertionError("Invalid perft growth");
        benchmark(board);
        System.out.println("PASS EngineVerification perft=" + depthOne + "/" + depthTwo);
    }

    private static void rejectedRootMovesAreNeverReused() {
        Board board = sampleBoard();
        Set<String> rejected = new HashSet<>();
        for (Move move : board.getLegalMoves(Board.RED)) rejected.add(move.toString());
        Move result = new CPUPlayer(Board.RED).findBestMove(board, 250, null, rejected);
        if (result != null) throw new AssertionError("A rejected root move was reused: " + result);
    }

    private static void rejectedMovesOverrideKnowledgeBase() {
        Board board = sampleBoard();
        Move cachedMove = board.getLegalMoves(Board.RED).get(0);
        WinningKnowledgeBase knowledge = new WinningKnowledgeBase();
        knowledge.putProven(board.exactKey(Board.RED), Board.RED, PackedMove.pack(cachedMove),
                4, 3, List.of(cachedMove));
        Move result = new CPUPlayer(Board.RED, knowledge)
                .findBestMove(board, 250, null, Set.of(cachedMove.toString()));
        if (result == null || result.equals(cachedMove))
            throw new AssertionError("A rejected move bypassed the knowledge-base filter");
    }

    private static void legacySuite() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            Main.main(new String[0]);
        } finally { System.setOut(original); }
        String output = captured.toString();
        long passes = output.lines().filter(line -> line.startsWith("PASS")).count();
        if (output.contains("FAIL") || passes != 26)
            throw new AssertionError("Legacy suite failed: expected 26 passes, got " + passes);
    }

    static long perft(Board board, int side, int depth) {
        if (depth == 0 || board.isTerminal()) return 1;
        int[] moves = new int[4096];
        int count = board.getLegalPackedMoves(side, moves);
        if (count == 0) return 1;
        long nodes = 0;
        for (int i = 0; i < count; i++) {
            Move move = PackedMove.unpack(moves[i]);
            MoveUndo undo = board.makeMove(move);
            nodes += perft(board, side == Board.RED ? Board.BLACK : Board.RED, depth - 1);
            board.unmakeMove(move, undo);
        }
        return nodes;
    }

    private static void randomizedMakeUnmake(Board initial, int iterations, long seed) {
        Board board = initial.copy();
        Random random = new Random(seed);
        int side = Board.RED;
        for (int i = 0; i < iterations; i++) {
            String key = board.positionKey();
            long hash = board.computeHash();
            int kingRow = board.getKingRow(), kingCol = board.getKingCol();
            int[] moves = new int[4096];
            int count = board.getLegalPackedMoves(side, moves);
            Set<Move> bitboardMoves = new HashSet<>();
            for (int m = 0; m < count; m++) bitboardMoves.add(PackedMove.unpack(moves[m]));
            Set<Move> referenceMoves = new HashSet<>(ReferenceRules.legalMoves(board.grid, side));
            if (!bitboardMoves.equals(referenceMoves))
                throw new AssertionError("Reference move mismatch at iteration " + i);
            if (count == 0) { board = initial.copy(); side = Board.RED; continue; }
            Move move = PackedMove.unpack(moves[random.nextInt(count)]);
            MoveUndo undo = board.makeMove(move);
            board.unmakeMove(move, undo);
            if (!key.equals(board.positionKey()) || hash != board.computeHash()
                    || kingRow != board.getKingRow() || kingCol != board.getKingCol())
                throw new AssertionError("make/unmake mismatch at iteration " + i);
            int[][] expected = ReferenceRules.apply(board.grid, move);
            board.applyMove(move);
            for (int row = 0; row < 13; row++) for (int col = 0; col < 13; col++)
                if (expected[row][col] != board.getPiece(row, col))
                    throw new AssertionError("Reference board mismatch at iteration " + i);
            if (ReferenceRules.winner(expected) != board.getWinner())
                throw new AssertionError("Reference winner mismatch at iteration " + i);
            if (board.isTerminal()) { board = initial.copy(); side = Board.RED; }
            else side = side == Board.RED ? Board.BLACK : Board.RED;
        }
    }

    private static void principalVariationIsLegal(Board initial) {
        Board board = initial.copy();
        SearchResult result = new CPUPlayer(Board.RED).findBestMoveDetailed(board, 250);
        int side = Board.RED;
        Set<PositionKey> seen = new HashSet<>();
        for (Move move : result.principalVariation()) {
            if (!seen.add(board.exactKey(side)) || !board.isValidMove(move))
                throw new AssertionError("Illegal principal variation");
            board.applyMove(move);
            side = side == Board.RED ? Board.BLACK : Board.RED;
        }
    }

    private static void knowledgeRoundTrip(Board board) throws Exception {
        WinningKnowledgeBase database = new WinningKnowledgeBase();
        Move best = board.getLegalMoves(Board.RED).get(0);
        database.putProven(board.exactKey(Board.RED), Board.RED, PackedMove.pack(best), 4, 3, List.of(best));
        Path file = Files.createTempFile("hnefatafl-knowledge", ".bin");
        try {
            database.save(file);
            WinningKnowledgeBase loaded = WinningKnowledgeBase.load(file);
            if (loaded.size() != 1 || loaded.get(board.exactKey(Board.RED)) == null)
                throw new AssertionError("Knowledge round-trip failed");
        } finally { Files.deleteIfExists(file); }
    }

    private static void benchmark(Board board) {
        long allocatedBefore = allocatedBytes();
        long start = System.nanoTime();
        CPUPlayer cpu = new CPUPlayer(Board.RED);
        SearchResult result = cpu.findBestMoveDetailed(board.copy(), 500);
        long elapsed = Math.max(1, (System.nanoTime() - start) / 1_000_000L);
        long allocatedAfter = allocatedBytes();
        long allocated = allocatedBefore >= 0 && allocatedAfter >= 0 ? allocatedAfter - allocatedBefore : -1;
        System.out.printf("benchmark nodes=%d depth=%d elapsedMs=%d nodesPerSecond=%d allocatedBytes=%d "
                        + "ttHits=%d/%d cutoffs=%d pv=%s%n",
                result.exploredNodes(), result.completedDepth(), elapsed,
                result.exploredNodes() * 1000L / elapsed, allocated, cpu.getTranspositionHits(),
                cpu.getTranspositionProbes(), cpu.getBetaCutoffs(), result.principalVariation());
    }

    private static long allocatedBytes() {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean memory
                && memory.isThreadAllocatedMemorySupported())
            return memory.getThreadAllocatedBytes(Thread.currentThread().threadId());
        return -1;
    }

    private static Board sampleBoard() {
        int[][] grid = new int[13][13];
        grid[6][6] = Board.KING;
        grid[6][4] = grid[6][8] = grid[4][6] = grid[8][6] = Board.BLACK;
        grid[0][3] = grid[0][9] = grid[3][0] = grid[9][0] = Board.RED;
        grid[12][3] = grid[12][9] = grid[3][12] = grid[9][12] = Board.RED;
        return new Board(grid);
    }
}
