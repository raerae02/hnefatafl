import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CPUPlayer {
    private static final int WINNING_MOVE_SCORE = 1000000;
    private static final long SEARCH_SAFETY_MARGIN_MS = 700;
    private static final long MIN_TIME_TO_START_DEPTH_MS = 300;
    private static final int MAX_TRANSPOSITION_ENTRIES = 200000;
    private static final long RED_TO_MOVE_HASH = 0x4d595df4d0f33173L;
    private static final long BLACK_TO_MOVE_HASH = 0x729da1f8717c43b5L;
    private final int cpuPlayer;
    private int numExploredNodes;
    private long searchEndTime;
    private boolean timeUp;
    private final Map<Long, TranspositionEntry> transpositionTable = new HashMap<>();

    public CPUPlayer(int cpuPlayer) {
        this.cpuPlayer = cpuPlayer;
    }

    private void resetSearchState(boolean clearTranspositionTable) {
        numExploredNodes = 0;
        searchEndTime = 0;
        timeUp = false;
        if (clearTranspositionTable) {
            transpositionTable.clear();
        }
    }

    private List<Move> orderMoves(Board board, List<Move> moves, int currentPlayer){
        List<ScoredMove> scoredMoves = new ArrayList<>();

        for (Move move : moves) {
            if (isTimeUp()) {
                timeUp = true;
                scoredMoves.add(new ScoredMove(move, 0));
                continue;
            }

            scoredMoves.add(new ScoredMove(move, scoreMove(board, move, currentPlayer)));
        }

        scoredMoves.sort((a, b) -> Integer.compare(b.score(), a.score()));

        List<Move> orderedMoves = new ArrayList<>();
        for (ScoredMove scoredMove : scoredMoves) {
            orderedMoves.add(scoredMove.move());
        }

        return orderedMoves;
    }

    private int scoreMove(Board board, Move move, int currentPlayer){
        int score = 0;

        if (isWinningMove(board, move, currentPlayer)){
            score += WINNING_MOVE_SCORE;
        }
        if (isCaptureMove(board, move, currentPlayer)){
            score += 5000;
        }

        if (currentPlayer == Board.RED){
            if (movesTowardKing(board, move)) score += 150;
            if (blocksKingOpenLine(board, move)) score += 5000;
        } else if (currentPlayer == Board.BLACK) {
            if (kingMovesTowardCorner(board, move)) score += 1500;
            if (opensKingLineToCorner(board, move)) score += 3000;
        }

        return score;
    }

    private boolean isWinningMove(Board board, Move move, int currentPlayer){
        MoveUndo undo = board.makeMove(move);
        boolean result = board.getWinner() == currentPlayer;
        board.unmakeMove(move, undo);
        return result;
    }

    private boolean isCaptureMove(Board board, Move move, int currentPlayer) {
        return board.countCapturesIfMove(move) > 0;
    }

    public Move findBestMoveAtDepth(Board board, int depth) {
        resetSearchState(true);
        return searchBestMoveAtDepth(board, depth, SearchAlgorithm.ALPHA_BETA);
    }

    public Move findBestMoveAtDepthMinMax(Board board, int depth) {
        resetSearchState(false);
        return searchBestMoveAtDepth(board, depth, SearchAlgorithm.MIN_MAX);
    }

    public int evaluateBestMoveAtDepth(Board board, int depth) {
        resetSearchState(true);
        return searchBestScoreAtDepth(board, depth, SearchAlgorithm.ALPHA_BETA);
    }

    public int evaluateBestMoveAtDepthMinMax(Board board, int depth) {
        resetSearchState(false);
        return searchBestScoreAtDepth(board, depth, SearchAlgorithm.MIN_MAX);
    }

    private Move searchBestMoveAtDepth(Board board, int depth, SearchAlgorithm algorithm) {
        Move bestMove = null;
        int bestScore = Integer.MIN_VALUE;
        List<Move> moves = orderMoves(board, board.getLegalMoves(cpuPlayer), cpuPlayer);

        for (Move move : moves) {
            MoveUndo undo = board.makeMove(move);
            int score = searchChild(board, depth, algorithm);
            board.unmakeMove(move, undo);

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }

        return bestMove;
    }

    private int searchBestScoreAtDepth(Board board, int depth, SearchAlgorithm algorithm) {
        int bestScore = Integer.MIN_VALUE;
        List<Move> moves = orderMoves(board, board.getLegalMoves(cpuPlayer), cpuPlayer);
        if (moves.isEmpty()) {
            return 0;
        }

        for (Move move : moves) {
            MoveUndo undo = board.makeMove(move);
            int score = searchChild(board, depth, algorithm);
            board.unmakeMove(move, undo);

            if (score > bestScore) {
                bestScore = score;
            }
        }

        return bestScore;
    }

    private int searchChild(Board board, int depth, SearchAlgorithm algorithm) {
        int childDepth = depth - 1;
        int nextPlayer = getOpponent(cpuPlayer);

        if (algorithm == SearchAlgorithm.ALPHA_BETA) {
            return alphaBeta(board, childDepth, Integer.MIN_VALUE, Integer.MAX_VALUE, nextPlayer);
        }
        return minMax(board, childDepth, nextPlayer);
    }

    // la fonction a utilisé dans le server pour prendre en compte le temps de 5 secondes
    public Move findBestMove(Board board, long timeLimitMillis){
        return findBestMove(board, timeLimitMillis, null);
    }

    public Move findBestMove(Board board, long timeLimitMillis, Map<String, Integer> positionHistory){
        return findBestMove(board, timeLimitMillis, positionHistory, null);
    }

    public Move findBestMove(Board board, long timeLimitMillis, Map<String, Integer> positionHistory, Set<String> forbiddenRootMoves){
        resetSearchState(true);
        long usableTime = Math.max(1, timeLimitMillis - SEARCH_SAFETY_MARGIN_MS);
        searchEndTime = System.currentTimeMillis() + usableTime;
        Move bestMoveOverall = null;
        int depth = 1;
        List<Move> movesAtRoot = orderMoves(board, board.getLegalMoves(cpuPlayer), cpuPlayer);

        if (movesAtRoot.isEmpty()) {
            return null;
        }

        movesAtRoot = filterRepeatedRootMoves(board, movesAtRoot, positionHistory);
        movesAtRoot = filterForbiddenRootMoves(movesAtRoot, forbiddenRootMoves);

        while (!isTimeUp() && getRemainingTimeMillis() >= MIN_TIME_TO_START_DEPTH_MS) {

            Move bestMoveAtThisDepth = null;
            int bestScoreAtThisDepth = Integer.MIN_VALUE;

            for (Move move : movesAtRoot) {
                if (isTimeUp()) {
                    timeUp = true;
                    break;
                }

                MoveUndo undo = board.makeMove(move);

                int score = alphaBeta(board, depth - 1,
                        Integer.MIN_VALUE, Integer.MAX_VALUE, getOpponent(cpuPlayer));

                board.unmakeMove(move, undo);

                if (timeUp) {
                    break;
                }

                if (score > bestScoreAtThisDepth) {
                    bestScoreAtThisDepth = score;
                    bestMoveAtThisDepth = move;
                }
            }

            if (!timeUp && bestMoveAtThisDepth != null) {
                bestMoveOverall = bestMoveAtThisDepth;
            }

            depth++;
        }

        if (bestMoveOverall == null) {
            return movesAtRoot.get(0);
        }

        return bestMoveOverall;
    }

    private List<Move> filterForbiddenRootMoves(List<Move> moves, Set<String> forbiddenRootMoves) {
        if (forbiddenRootMoves == null || forbiddenRootMoves.isEmpty()) {
            return moves;
        }

        List<Move> allowedMoves = new ArrayList<>();

        for (Move move : moves) {
            if (!forbiddenRootMoves.contains(move.toString())) {
                allowedMoves.add(move);
            }
        }

        if (allowedMoves.isEmpty()) {
            return moves;
        }

        return allowedMoves;
    }

    private List<Move> filterRepeatedRootMoves(Board board, List<Move> moves, Map<String, Integer> positionHistory) {
        if (positionHistory == null || positionHistory.isEmpty()) {
            return moves;
        }

        List<Move> nonRepeatedMoves = new ArrayList<>();

        for (Move move : moves) {
            MoveUndo undo = board.makeMove(move);
            boolean winningMove = board.getWinner() == cpuPlayer;
            boolean repeated = positionHistory.getOrDefault(board.positionKey(), 0) > 0;
            board.unmakeMove(move, undo);

            if (winningMove || !repeated) {
                nonRepeatedMoves.add(move);
            }
        }

        if (nonRepeatedMoves.isEmpty()) {
            return moves;
        }

        return nonRepeatedMoves;
    }

    public int getNumExploredNodes() {
        return numExploredNodes;
    }

    private int minMax(Board board, int depth, int currentPlayer) {
        numExploredNodes++;

        if (depth == 0 || board.isTerminal()) {
            return board.evaluate(cpuPlayer);
        }

        List<Move> moves = board.getLegalMoves(currentPlayer);
        if (moves.isEmpty()) {
            return 0;
        }
        moves = orderMoves(board, moves, currentPlayer);

        int nextPlayer = getOpponent(currentPlayer);

        if (currentPlayer == cpuPlayer) {
            int bestScore = Integer.MIN_VALUE;

            for (Move move : moves) {
                MoveUndo undo = board.makeMove(move);
                bestScore = Math.max(bestScore, minMax(board, depth - 1, nextPlayer));
                board.unmakeMove(move, undo);
            }

            return bestScore;
        }

        int bestScore = Integer.MAX_VALUE;

        for (Move move : moves) {
            MoveUndo undo = board.makeMove(move);
            bestScore = Math.min(bestScore, minMax(board, depth - 1, nextPlayer));
            board.unmakeMove(move, undo);
        }

        return bestScore;
    }

    private int alphaBeta(Board board, int depth, int alpha, int beta, int currentPlayer) {
        numExploredNodes++;

        if (isTimeUp()) {
            timeUp = true;
            return board.evaluate(cpuPlayer);
        }

        int originalAlpha = alpha;
        int originalBeta = beta;
        long hash = computeTranspositionKey(board, currentPlayer);
        TranspositionEntry entry = transpositionTable.get(hash);

        if (entry != null && entry.depth() >= depth) {
            if (entry.flag() == TranspositionFlag.EXACT) {
                return entry.score();
            } else if (entry.flag() == TranspositionFlag.LOWER_BOUND) {
                alpha = Math.max(alpha, entry.score());
            } else if (entry.flag() == TranspositionFlag.UPPER_BOUND) {
                beta = Math.min(beta, entry.score());
            }

            if (alpha >= beta) {
                return entry.score();
            }
        }

        if (depth == 0 || board.isTerminal()) {
            int score = board.evaluate(cpuPlayer);
            storeTransposition(hash, depth, score, TranspositionFlag.EXACT, null);
            return score;
        }

        List<Move> moves = board.getLegalMoves(currentPlayer);
        if (moves.isEmpty()) {
            storeTransposition(hash, depth, 0, TranspositionFlag.EXACT, null);
            return 0;
        }
        moves = orderMoves(board, moves, currentPlayer);

        int nextPlayer = getOpponent(currentPlayer);
        Move bestMove = null;

        if (currentPlayer == cpuPlayer) {
            int bestScore = Integer.MIN_VALUE;

            for (Move move : moves) {
                if (isTimeUp()) {
                    timeUp = true;
                    return bestScore;
                }
                MoveUndo undo = board.makeMove(move);

                int score = alphaBeta(board, depth - 1, alpha, beta, nextPlayer);

                board.unmakeMove(move, undo);


                if (score > bestScore) {
                    bestScore = score;
                    bestMove = move;
                }
                alpha = Math.max(alpha, bestScore);

                if (beta <= alpha) {
                    break;
                }
            }

            storeTransposition(hash, depth, bestScore, getTranspositionFlag(bestScore, originalAlpha, originalBeta), bestMove);
            return bestScore;
        }

        int bestScore = Integer.MAX_VALUE;

        for (Move move : moves) {
            if (isTimeUp()) {
                timeUp = true;
                return bestScore;
            }
            MoveUndo undo = board.makeMove(move);

            int score = alphaBeta(board, depth - 1, alpha, beta, nextPlayer);

            board.unmakeMove(move, undo);

            if (score < bestScore) {
                bestScore = score;
                bestMove = move;
            }
            beta = Math.min(beta, bestScore);

            if (beta <= alpha) {
                break;
            }
        }

        storeTransposition(hash, depth, bestScore, getTranspositionFlag(bestScore, originalAlpha, originalBeta), bestMove);
        return bestScore;
    }

    private int getOpponent(int player) {
        return player == Board.RED ? Board.BLACK : Board.RED;
    }

    private boolean isTimeUp() {
        return searchEndTime > 0 && System.currentTimeMillis() >= searchEndTime;
    }

    private long getRemainingTimeMillis() {
        if (searchEndTime <= 0) {
            return Long.MAX_VALUE;
        }
        return searchEndTime - System.currentTimeMillis();
    }
    private boolean movesTowardKing(Board board, Move move) {
        int kingRow = board.getKingRow();
        int kingCol = board.getKingCol();

        int distanceBefore = distanceManhattan(move.fromRow, move.fromCol, kingRow, kingCol);
        int distanceAfter = distanceManhattan(move.toRow, move.toCol, kingRow, kingCol);

        return distanceAfter < distanceBefore;
    }

    private int distanceManhattan(int r1, int c1, int r2, int c2) {
        return Math.abs(r1 - r2) + Math.abs(c1 - c2);
    }

    private boolean blocksKingOpenLine(Board board, Move move) {
        if (board.getPiece(move.fromRow, move.fromCol) != Board.RED) {
            return false;
        }

        int openBefore = board.getOpenKingLines();
        if (openBefore == 0) {
            return false;
        }

        MoveUndo undo = board.makeMove(move);

        int openAfter = board.getOpenKingLines();
        board.unmakeMove(move, undo);
        return openAfter < openBefore;
    }

    private boolean kingMovesTowardCorner(Board board, Move move) {
        if (board.getPiece(move.fromRow, move.fromCol) != Board.KING) {
            return false;
        }

        int distanceBefore = minDistanceToCorner(move.fromRow, move.fromCol);
        int distanceAfter = minDistanceToCorner(move.toRow, move.toCol);

        return distanceAfter < distanceBefore;
    }

    private boolean opensKingLineToCorner(Board board, Move move) {
        int openBefore = board.getOpenKingLines();

        MoveUndo undo = board.makeMove(move);
        int openAfter = board.getOpenKingLines();
        board.unmakeMove(move, undo);

        return openAfter > openBefore;
    }

    private int minDistanceToCorner(int row, int col) {
        int best = Integer.MAX_VALUE;

        for (int[] corner : Board.CORNERS) {
            int distance = distanceManhattan(row, col, corner[0], corner[1]);
            best = Math.min(best, distance);
        }

        return best;
    }

    private TranspositionFlag getTranspositionFlag(int score, int originalAlpha, int originalBeta) {
        if (score <= originalAlpha) {
            return TranspositionFlag.UPPER_BOUND;
        }
        if (score >= originalBeta) {
            return TranspositionFlag.LOWER_BOUND;
        }
        return TranspositionFlag.EXACT;
    }

    private void storeTransposition(long hash, int depth, int score, TranspositionFlag flag, Move bestMove) {
        if (transpositionTable.size() >= MAX_TRANSPOSITION_ENTRIES) {
            return;
        }

        TranspositionEntry entry = new TranspositionEntry(depth, score, flag, bestMove);
        transpositionTable.put(hash, entry);
    }

    private long computeTranspositionKey(Board board, int currentPlayer) {
        long hash = board.computeHash();
        return hash ^ (currentPlayer == Board.RED ? RED_TO_MOVE_HASH : BLACK_TO_MOVE_HASH);
    }

    private enum SearchAlgorithm {
        ALPHA_BETA,
        MIN_MAX
    }

    private enum TranspositionFlag {
        EXACT,
        LOWER_BOUND,
        UPPER_BOUND
    }

    private record ScoredMove(Move move, int score) {}

    private record TranspositionEntry(int depth, int score, TranspositionFlag flag, Move bestMove) {}
}
