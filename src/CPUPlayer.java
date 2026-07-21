import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Iterative-deepening PVS search with a fixed-size transposition table. */
public class CPUPlayer {
    static final int WIN_SCORE = 1_000_000;
    static final int MATE_THRESHOLD = 900_000;
    private static final long DEFAULT_SAFETY_MARGIN_MS = 150;
    private static final long MIN_TIME_TO_START_DEPTH_MS = 20;
    private static final int MAX_PLY = 400;
    private static final int MAX_MOVES = 4096;
    private static final int MAX_SEARCH_PLY = 128;
    private static final int TT_SIZE = 1 << 18;
    private static final int TT_MASK = TT_SIZE - 1;
    private static final int EXACT = 1, LOWER = 2, UPPER = 3;
    private static final long RED_TO_MOVE_HASH = 0x4d595df4d0f33173L;
    private static final long BLACK_TO_MOVE_HASH = 0x729da1f8717c43b5L;

    private final int cpuPlayer;
    private final WinningKnowledgeBase knowledgeBase;
    private long exploredNodes;
    private long ttProbes;
    private long ttHits;
    private long betaCutoffs;
    private long searchEndNanos;
    private boolean timeUp;
    private int generation;
    private Map<String, Integer> gameHistory;
    private final int[][] moveBuffers = new int[MAX_SEARCH_PLY][MAX_MOVES];
    private final int[][] scoreBuffers = new int[MAX_SEARCH_PLY][MAX_MOVES];
    private final int[][] killerMoves = new int[MAX_SEARCH_PLY][2];
    private final int[][] history = new int[2][1 << 16];
    private final SearchUndo[] undoBuffers = new SearchUndo[MAX_SEARCH_PLY];
    private final long[] pathKeys = new long[MAX_PLY + 1];

    private final long[] ttKeys = new long[TT_SIZE];
    private final int[] ttDepths = new int[TT_SIZE];
    private final int[] ttScores = new int[TT_SIZE];
    private final int[] ttMoves = new int[TT_SIZE];
    private final byte[] ttFlags = new byte[TT_SIZE];
    private final int[] ttGenerations = new int[TT_SIZE];

    public CPUPlayer(int cpuPlayer) {
        this(cpuPlayer, null);
    }

    public CPUPlayer(int cpuPlayer, WinningKnowledgeBase knowledgeBase) {
        this.cpuPlayer = cpuPlayer;
        this.knowledgeBase = knowledgeBase;
        for (int[] killers : killerMoves) Arrays.fill(killers, PackedMove.NONE);
        for (int i = 0; i < undoBuffers.length; i++) undoBuffers[i] = new SearchUndo();
        Arrays.fill(ttMoves, PackedMove.NONE);
    }

    public Move findBestMoveAtDepth(Board board, int depth) {
        return searchFixedDepth(board, depth, false).bestMove();
    }

    public Move findBestMoveAtDepthMinMax(Board board, int depth) {
        return searchFixedDepth(board, depth, true).bestMove();
    }

    public int evaluateBestMoveAtDepth(Board board, int depth) {
        return searchFixedDepth(board, depth, false).score();
    }

    public int evaluateBestMoveAtDepthMinMax(Board board, int depth) {
        return searchFixedDepth(board, depth, true).score();
    }

    public Move findBestMove(Board board, long timeLimitMillis) {
        return findBestMove(board, timeLimitMillis, null, null);
    }

    public Move findBestMove(Board board, long timeLimitMillis, Map<String, Integer> positionHistory) {
        return findBestMove(board, timeLimitMillis, positionHistory, null);
    }

    public Move findBestMove(Board board, long timeLimitMillis, Map<String, Integer> positionHistory,
                             Set<String> forbiddenRootMoves) {
        return findBestMoveDetailed(board, timeLimitMillis, positionHistory, forbiddenRootMoves).bestMove();
    }

    public SearchResult findBestMoveDetailed(Board board, long timeLimitMillis) {
        return findBestMoveDetailed(board, timeLimitMillis, null, null);
    }

    public SearchResult findBestMoveDetailed(Board board, long timeLimitMillis,
                                             Map<String, Integer> positionHistory,
                                             Set<String> forbiddenRootMoves) {
        resetSearch();
        gameHistory = positionHistory;
        long start = System.nanoTime();
        long usableMillis = Math.max(1, timeLimitMillis - Math.min(DEFAULT_SAFETY_MARGIN_MS, timeLimitMillis / 4));
        searchEndNanos = start + usableMillis * 1_000_000L;

        int[] roots = moveBuffers[0];
        int rootCount = board.getLegalPackedMoves(cpuPlayer, roots);
        rootCount = filterRootMoves(board, roots, rootCount, positionHistory, forbiddenRootMoves);
        if (rootCount == 0) return result(null, 0, 0, start, List.of());

        PositionKey rootKey = board.exactKey(cpuPlayer);
        KnowledgeEntry knowledge = knowledgeBase == null ? null : knowledgeBase.get(rootKey);
        if (knowledge != null && knowledge.bestMove() != PackedMove.NONE) {
            Move known = PackedMove.unpack(knowledge.bestMove());
            // The root list has already removed server-rejected moves. A cached
            // recommendation must never bypass that protocol constraint.
            if (board.isValidMove(known) && containsMove(roots, rootCount, knowledge.bestMove())) {
                if (knowledge.type() == KnowledgeType.PROVEN && knowledge.winner() == cpuPlayer) {
                    List<Move> pv = knowledge.principalVariation().stream().map(PackedMove::unpack).toList();
                    return result(known, WIN_SCORE - Math.max(0, knowledge.distanceToWin()),
                            knowledge.searchDepth(), start, pv);
                }
            }
        }

        Move bestMove = PackedMove.unpack(roots[0]);
        int bestScore = 0;
        int completedDepth = 0;
        List<Move> bestPv = List.of(bestMove);
        int previousScore = 0;

        for (int depth = 1; !isTimeUp() && remainingMillis() >= MIN_TIME_TO_START_DEPTH_MS; depth++) {
            int window = depth <= 2 ? WIN_SCORE : 75;
            int alpha = Math.max(-WIN_SCORE, previousScore - window);
            int beta = Math.min(WIN_SCORE, previousScore + window);
            RootResult iteration = searchRoot(board, roots, rootCount, depth, alpha, beta);
            if (!timeUp && (iteration.score <= alpha || iteration.score >= beta)) {
                iteration = searchRoot(board, roots, rootCount, depth, -WIN_SCORE, WIN_SCORE);
            }
            if (timeUp) break;
            bestMove = PackedMove.unpack(iteration.move);
            bestScore = iteration.score;
            previousScore = bestScore;
            completedDepth = depth;
            bestPv = extractPrincipalVariation(board, cpuPlayer, depth);
            promoteRootMove(roots, rootCount, iteration.move);
            if (Math.abs(bestScore) >= WIN_SCORE - depth) break;
        }
        SearchResult result = result(bestMove, bestScore, completedDepth, start, bestPv);
        if (knowledgeBase != null && result.isForcedWin() && result.bestMove() != null)
            knowledgeBase.putProven(rootKey, cpuPlayer, PackedMove.pack(result.bestMove()), completedDepth,
                    result.mateDistance(), result.principalVariation());
        return result;
    }

    private SearchResult searchFixedDepth(Board board, int depth, boolean plainMinMax) {
        resetSearch();
        long start = System.nanoTime();
        searchEndNanos = 0;
        int[] roots = moveBuffers[0];
        int count = board.getLegalPackedMoves(cpuPlayer, roots);
        if (count == 0) return result(null, 0, depth, start, List.of());
        if (plainMinMax) {
            int bestMove = roots[0];
            int best = -WIN_SCORE;
            for (int i = 0; i < count; i++) {
                SearchUndo undo = undoBuffers[0];
                board.makeMovePacked(roots[i], undo);
                int score = -minMax(board, depth - 1, opponent(cpuPlayer), 1);
                board.unmakeMovePacked(roots[i], undo);
                if (score > best) { best = score; bestMove = roots[i]; }
            }
            return result(PackedMove.unpack(bestMove), best, depth, start, List.of(PackedMove.unpack(bestMove)));
        }
        RootResult root = searchRoot(board, roots, count, depth, -WIN_SCORE, WIN_SCORE);
        List<Move> pv = extractPrincipalVariation(board, cpuPlayer, depth);
        return result(PackedMove.unpack(root.move), root.score, depth, start, pv);
    }

    private RootResult searchRoot(Board board, int[] roots, int count, int depth, int alpha, int beta) {
        orderMoves(board, roots, count, cpuPlayer, 0, probeMove(positionHash(board, cpuPlayer)));
        int originalAlpha = alpha;
        int bestMove = roots[0];
        int bestScore = -WIN_SCORE;
        pathKeys[0] = positionHash(board, cpuPlayer);
        for (int i = 0; i < count; i++) {
            if (isTimeUp()) { timeUp = true; break; }
            SearchUndo undo = undoBuffers[0];
            board.makeMovePacked(roots[i], undo);
            int score;
            if (i == 0) {
                score = -pvs(board, depth - 1, -beta, -alpha, opponent(cpuPlayer), 1);
            } else {
                score = -pvs(board, depth - 1, -alpha - 1, -alpha, opponent(cpuPlayer), 1);
                if (!timeUp && score > alpha && score < beta) {
                    score = -pvs(board, depth - 1, -beta, -alpha, opponent(cpuPlayer), 1);
                }
            }
            board.unmakeMovePacked(roots[i], undo);
            if (timeUp) break;
            if (score > bestScore) { bestScore = score; bestMove = roots[i]; }
            alpha = Math.max(alpha, score);
            if (alpha >= beta) break;
        }
        if (!timeUp) store(positionHash(board, cpuPlayer), depth, bestScore,
                bestScore <= originalAlpha ? UPPER : bestScore >= beta ? LOWER : EXACT, bestMove, 0);
        return new RootResult(bestMove, bestScore);
    }

    private int pvs(Board board, int depth, int alpha, int beta, int side, int ply) {
        exploredNodes++;
        if ((exploredNodes & 1023) == 0 && isTimeUp()) { timeUp = true; return evaluate(board, side); }
        int winner = board.getWinner();
        if (winner != 0) return winner == side ? WIN_SCORE - ply : -WIN_SCORE + ply;
        if (ply >= MAX_PLY || isThreefold(board, side, ply)) return 0;
        if (depth <= 0 || ply >= MAX_SEARCH_PLY - 2) return quiescence(board, alpha, beta, side, ply, 2);

        long key = positionHash(board, side);
        int index = index(key);
        ttProbes++;
        int originalAlpha = alpha;
        int ttMove = PackedMove.NONE;
        if (ttGenerations[index] != 0 && ttKeys[index] == key) {
            ttHits++;
            ttMove = ttMoves[index];
            if (ttDepths[index] >= depth) {
                int score = scoreFromTable(ttScores[index], ply);
                if (ttFlags[index] == EXACT) return score;
                if (ttFlags[index] == LOWER) alpha = Math.max(alpha, score);
                else if (ttFlags[index] == UPPER) beta = Math.min(beta, score);
                if (alpha >= beta) return score;
            }
        }

        int[] moves = moveBuffers[ply];
        int count = board.getLegalPackedMoves(side, moves);
        if (count == 0) return 0;
        orderMoves(board, moves, count, side, ply, ttMove);
        pathKeys[ply] = key;
        int best = -WIN_SCORE;
        int bestMove = moves[0];
        for (int i = 0; i < count; i++) {
            SearchUndo undo = undoBuffers[ply];
            board.makeMovePacked(moves[i], undo);
            int score;
            if (i == 0) score = -pvs(board, depth - 1, -beta, -alpha, opponent(side), ply + 1);
            else {
                score = -pvs(board, depth - 1, -alpha - 1, -alpha, opponent(side), ply + 1);
                if (!timeUp && score > alpha && score < beta)
                    score = -pvs(board, depth - 1, -beta, -alpha, opponent(side), ply + 1);
            }
            board.unmakeMovePacked(moves[i], undo);
            if (timeUp) return best == -WIN_SCORE ? evaluate(board, side) : best;
            if (score > best) { best = score; bestMove = moves[i]; }
            if (score > alpha) alpha = score;
            if (alpha >= beta) {
                betaCutoffs++;
                rememberCutoff(side, ply, moves[i], depth);
                break;
            }
        }
        store(key, depth, best, best <= originalAlpha ? UPPER : best >= beta ? LOWER : EXACT, bestMove, ply);
        return best;
    }

    private int quiescence(Board board, int alpha, int beta, int side, int ply, int remaining) {
        exploredNodes++;
        int winner = board.getWinner();
        if (winner != 0) return winner == side ? WIN_SCORE - ply : -WIN_SCORE + ply;
        int standPat = evaluate(board, side);
        if (standPat >= beta) return standPat;
        if (standPat > alpha) alpha = standPat;
        if (remaining == 0) return standPat;

        int[] moves = moveBuffers[ply];
        int count = board.getLegalPackedMoves(side, moves);
        for (int i = 0; i < count; i++) {
            int packed = moves[i];
            int from = PackedMove.from(packed), to = PackedMove.to(packed);
            int piece = board.getPiece(BitBoard169.row(from), BitBoard169.col(from));
            boolean tactical = board.countCapturesIfPacked(packed) > 0
                    || (piece == Board.KING && isCorner(to))
                    || (side == Board.RED && adjacentToKing(board, to));
            if (!tactical) continue;
            SearchUndo undo = undoBuffers[ply];
            board.makeMovePacked(packed, undo);
            int score = -quiescence(board, -beta, -alpha, opponent(side), ply + 1, remaining - 1);
            board.unmakeMovePacked(packed, undo);
            if (score >= beta) return score;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    private boolean adjacentToKing(Board board, int square) {
        return Math.abs(BitBoard169.row(square) - board.getKingRow())
                + Math.abs(BitBoard169.col(square) - board.getKingCol()) == 1;
    }

    private boolean isCorner(int square) {
        int row = BitBoard169.row(square), col = BitBoard169.col(square);
        return (row == 0 || row == Board.BOARD_SIZE - 1) && (col == 0 || col == Board.BOARD_SIZE - 1);
    }

    private int minMax(Board board, int depth, int side, int ply) {
        exploredNodes++;
        int winner = board.getWinner();
        if (winner != 0) return winner == side ? WIN_SCORE - ply : -WIN_SCORE + ply;
        if (depth <= 0 || ply >= MAX_SEARCH_PLY - 1) return evaluate(board, side);
        int[] moves = moveBuffers[Math.min(ply, MAX_SEARCH_PLY - 1)];
        int count = board.getLegalPackedMoves(side, moves);
        if (count == 0) return 0;
        int best = -WIN_SCORE;
        for (int i = 0; i < count; i++) {
            SearchUndo undo = undoBuffers[Math.min(ply, MAX_SEARCH_PLY - 1)];
            board.makeMovePacked(moves[i], undo);
            best = Math.max(best, -minMax(board, depth - 1, opponent(side), ply + 1));
            board.unmakeMovePacked(moves[i], undo);
        }
        return best;
    }

    private void orderMoves(Board board, int[] moves, int count, int side, int ply, int ttMove) {
        int[] scores = scoreBuffers[ply];
        int sideIndex = side == Board.RED ? 0 : 1;
        for (int i = 0; i < count; i++) {
            int packed = moves[i];
            int score = history[sideIndex][packed & 0xffff];
            if (packed == ttMove) score += 2_000_000;
            if (packed == killerMoves[ply][0]) score += 80_000;
            else if (packed == killerMoves[ply][1]) score += 60_000;
            int captures = board.countCapturesIfPacked(packed);
            if (captures > 0) score += 100_000 + captures * 10_000;
            SearchUndo undo = undoBuffers[ply];
            board.makeMovePacked(packed, undo);
            if (board.getWinner() == side) score += 1_000_000;
            board.unmakeMovePacked(packed, undo);
            scores[i] = score;
        }
        for (int i = 1; i < count; i++) {
            int move = moves[i], score = scores[i], j = i - 1;
            while (j >= 0 && scores[j] < score) {
                moves[j + 1] = moves[j]; scores[j + 1] = scores[j]; j--;
            }
            moves[j + 1] = move; scores[j + 1] = score;
        }
    }

    private void rememberCutoff(int side, int ply, int move, int depth) {
        if (killerMoves[ply][0] != move) {
            killerMoves[ply][1] = killerMoves[ply][0];
            killerMoves[ply][0] = move;
        }
        int sideIndex = side == Board.RED ? 0 : 1;
        int index = move & 0xffff;
        history[sideIndex][index] = Math.min(1_000_000, history[sideIndex][index] + depth * depth);
    }

    private boolean isThreefold(Board board, int side, int ply) {
        long key = positionHash(board, side);
        int occurrences = 1 + (gameHistory == null ? 0
                : gameHistory.getOrDefault(board.positionKey(side), 0));
        if (occurrences >= 3) return true;
        for (int i = ply - 2; i >= 0; i -= 2) if (pathKeys[i] == key && ++occurrences >= 3) return true;
        return false;
    }

    private List<Move> extractPrincipalVariation(Board board, int side, int depth) {
        List<Move> pv = new ArrayList<>();
        List<MoveUndo> undos = new ArrayList<>();
        for (int i = 0; i < depth; i++) {
            int moveCode = probeMove(positionHash(board, side));
            if (moveCode == PackedMove.NONE) break;
            Move move = PackedMove.unpack(moveCode);
            if (!board.isValidMove(move)) break;
            pv.add(move);
            undos.add(board.makeMove(move));
            if (board.isTerminal()) break;
            side = opponent(side);
        }
        for (int i = pv.size() - 1; i >= 0; i--) board.unmakeMove(pv.get(i), undos.get(i));
        return pv;
    }

    private int filterRootMoves(Board board, int[] moves, int count, Map<String, Integer> positions,
                                Set<String> forbidden) {
        // A server-rejected move is never a valid fallback. Remove it before applying
        // the softer repetition preference below.
        int allowedCount = 0;
        for (int i = 0; i < count; i++) {
            if (forbidden != null && !forbidden.isEmpty()
                    && forbidden.contains(PackedMove.unpack(moves[i]).toString())) {
                continue;
            }
            moves[allowedCount++] = moves[i];
        }
        if (allowedCount == 0) return 0;
        if (positions == null || positions.isEmpty()) return allowedCount;

        int nonRepeatedCount = 0;
        for (int i = 0; i < allowedCount; i++) {
            SearchUndo undo = undoBuffers[0];
            board.makeMovePacked(moves[i], undo);
            boolean win = board.getWinner() == cpuPlayer;
            boolean repeat = positions.getOrDefault(board.positionKey(opponent(cpuPlayer)), 0) >= 2;
            board.unmakeMovePacked(moves[i], undo);
            if (win || !repeat) moves[nonRepeatedCount++] = moves[i];
        }
        // Repetition avoidance is a preference: retain the allowed moves if it would
        // otherwise leave no move. Unlike rejected moves, these may be reconsidered.
        return nonRepeatedCount == 0 ? allowedCount : nonRepeatedCount;
    }

    private void promoteRootMove(int[] moves, int count, int best) {
        for (int i = 0; i < count; i++) if (moves[i] == best) {
            System.arraycopy(moves, 0, moves, 1, i);
            moves[0] = best;
            return;
        }
    }

    private boolean containsMove(int[] moves, int count, int candidate) {
        for (int i = 0; i < count; i++) if (moves[i] == candidate) return true;
        return false;
    }

    private void resetSearch() {
        exploredNodes = 0;
        ttProbes = 0;
        ttHits = 0;
        betaCutoffs = 0;
        timeUp = false;
        searchEndNanos = 0;
        generation++;
        gameHistory = null;
        Arrays.fill(pathKeys, 0L);
    }

    private SearchResult result(Move move, int score, int depth, long start, List<Move> pv) {
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        int mateDistance = Math.abs(score) >= MATE_THRESHOLD ? WIN_SCORE - Math.abs(score) : -1;
        return new SearchResult(move, score, depth, exploredNodes, elapsed, mateDistance, pv);
    }

    private long positionHash(Board board, int side) {
        return board.computeHash() ^ (side == Board.RED ? RED_TO_MOVE_HASH : BLACK_TO_MOVE_HASH);
    }

    private int index(long key) { return (int) (key ^ (key >>> 32)) & TT_MASK; }
    private int probeMove(long key) {
        int i = index(key);
        return ttGenerations[i] != 0 && ttKeys[i] == key ? ttMoves[i] : PackedMove.NONE;
    }

    private void store(long key, int depth, int score, int flag, int move, int ply) {
        int i = index(key);
        if (ttKeys[i] != key && ttGenerations[i] == generation && ttDepths[i] > depth) return;
        if (ttKeys[i] == key && ttDepths[i] > depth && ttFlags[i] == EXACT) return;
        ttKeys[i] = key; ttDepths[i] = depth; ttScores[i] = scoreToTable(score, ply); ttFlags[i] = (byte) flag;
        ttMoves[i] = move; ttGenerations[i] = generation;
    }

    private int scoreToTable(int score, int ply) {
        if (score >= MATE_THRESHOLD) return score + ply;
        if (score <= -MATE_THRESHOLD) return score - ply;
        return score;
    }

    private int scoreFromTable(int score, int ply) {
        if (score >= MATE_THRESHOLD) return score - ply;
        if (score <= -MATE_THRESHOLD) return score + ply;
        return score;
    }

    private int opponent(int player) { return player == Board.RED ? Board.BLACK : Board.RED; }
    private int evaluate(Board board, int side) { return board.evaluate(side); }
    private boolean isTimeUp() { return searchEndNanos > 0 && System.nanoTime() >= searchEndNanos; }
    private long remainingMillis() { return searchEndNanos == 0 ? Long.MAX_VALUE : (searchEndNanos - System.nanoTime()) / 1_000_000L; }
    public int getNumExploredNodes() { return exploredNodes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) exploredNodes; }
    public long getTranspositionProbes() { return ttProbes; }
    public long getTranspositionHits() { return ttHits; }
    public long getBetaCutoffs() { return betaCutoffs; }
    int getSide() { return cpuPlayer; }

    private record RootResult(int move, int score) {}
}
