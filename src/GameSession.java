import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class GameSession {
    private final Map<String, Integer> positionHistory = new HashMap<>();
    private final Map<String, Set<String>> rejectedMovesByPosition = new HashMap<>();
    private Move lastOwnMove;
    private MoveUndo lastOwnUndo;

    void reset(Board board) {
        positionHistory.clear();
        rejectedMovesByPosition.clear();
        clearLastOwnMove();
        recordPosition(board);
    }

    Map<String, Integer> positionHistory() {
        return positionHistory;
    }

    void recordPosition(Board board) {
        String key = board.positionKey();
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

    boolean isRepeatedPosition(Board board) {
        String key = board.positionKey();
        return positionHistory.getOrDefault(key, 0) >= 2;
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
        lastOwnMove = move;
        lastOwnUndo = undo;
    }

    Move lastOwnMove() {
        return lastOwnMove;
    }

    void revertLastOwnMove(Board board) {
        if (lastOwnMove == null || lastOwnUndo == null) {
            return;
        }

        forgetPosition(board);
        board.unmakeMove(lastOwnMove, lastOwnUndo);
        clearLastOwnMove();
    }

    void clearLastOwnMove() {
        lastOwnMove = null;
        lastOwnUndo = null;
    }
}
