import java.util.List;

final class TrainingSample {
    final byte[] position;
    final int sideToMove;
    final Move chosenMove;
    final int ply;
    final List<ScoredMove> rankedMoves;

    TrainingSample(byte[] position, int sideToMove, Move chosenMove,
                   int ply, List<ScoredMove> rankedMoves) {
        this.position = position;
        this.sideToMove = sideToMove;
        this.chosenMove = chosenMove;
        this.ply = ply;
        this.rankedMoves = rankedMoves;
    }
}
