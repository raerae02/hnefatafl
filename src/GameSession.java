import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class GameSession {
    private final Map<String, Integer> positionHistory = new HashMap<>();
    private final Map<String, Set<String>> rejectedMovesByPosition = new HashMap<>();
    private Move lastOwnMove;
    private MoveUndo lastOwnUndo;
    private int lastOwnNextSide;

    void reset(Board board) {
        positionHistory.clear();
        rejectedMovesByPosition.clear();
        clearLastOwnMove();
        recordPosition(board, Board.RED);
    }

    Map<String, Integer> positionHistory() {
        return positionHistory;
    }

    void recordPosition(Board board) {
        recordPosition(board, 0);
    }

    void recordPosition(Board board, int sideToMove) {
        String key = sideToMove == 0 ? board.positionKey() : board.positionKey(sideToMove);
        int count = positionHistory.getOrDefault(key, 0);
        positionHistory.put(key, count + 1);
    }

    void forgetPosition(Board board) {
        String key = board.positionKey();
        int count = positionHistory.getOrDefault(key, 0);

        if (count <= 1) {
            positionHistory.remove(key);
        } else {
            positionHistory.put(key, count - 1);
        }
    }

    void forgetPosition(Board board, int sideToMove) {
        String key = board.positionKey(sideToMove);
        int count = positionHistory.getOrDefault(key, 0);
        if (count <= 1) positionHistory.remove(key);
        else positionHistory.put(key, count - 1);
    }

    boolean isRepeatedPosition(Board board) {
        String key = board.positionKey();
        return positionHistory.getOrDefault(key, 0) >= 2;
    }

    boolean isRepeatedPosition(Board board, int sideToMove) {
        return positionHistory.getOrDefault(board.positionKey(sideToMove), 0) >= 3;
    }

    void rememberRejectedMove(Board board, Move move) {
        if (move == null) {
            return;
        }

        String positionKey = board.positionKey();
        Set<String> rejectedMoves = rejectedMovesByPosition.get(positionKey);

        if (rejectedMoves == null) {
            rejectedMoves = new HashSet<>();
            rejectedMovesByPosition.put(positionKey, rejectedMoves);
        }

        rejectedMoves.add(move.toString());
        System.out.println("Coup refuse memorise pour cette position: " + move);
    }

    Set<String> getRejectedMoves(Board board) {
        Set<String> rejectedMoves = rejectedMovesByPosition.get(board.positionKey());
        if (rejectedMoves == null) {
            return new HashSet<>();
        }
        return rejectedMoves;
    }

    void rememberOwnMove(Move move, MoveUndo undo) {
        rememberOwnMove(move, undo, 0);
    }

    void rememberOwnMove(Move move, MoveUndo undo, int nextSide) {
        lastOwnMove = move;
        lastOwnUndo = undo;
        lastOwnNextSide = nextSide;
    }

    Move lastOwnMove() {
        return lastOwnMove;
    }

    void revertLastOwnMove(Board board) {
        if (lastOwnMove == null || lastOwnUndo == null) {
            return;
        }

        if (lastOwnNextSide == 0) forgetPosition(board);
        else forgetPosition(board, lastOwnNextSide);
        board.unmakeMove(lastOwnMove, lastOwnUndo);
        clearLastOwnMove();
    }

    void clearLastOwnMove() {
        lastOwnMove = null;
        lastOwnUndo = null;
        lastOwnNextSide = 0;
    }
}
