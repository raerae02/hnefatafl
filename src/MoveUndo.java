import java.util.ArrayList;
import java.util.List;

class MoveUndo {
    final int movedPiece;
    final int oldKingRow;
    final int oldKingCol;
    final long oldHash;
    final List<CapturedPiece> capturedPieces;

    MoveUndo(int movedPiece, int oldKingRow, int oldKingCol, long oldHash) {
        this.movedPiece = movedPiece;
        this.oldKingRow = oldKingRow;
        this.oldKingCol = oldKingCol;
        this.oldHash = oldHash;
        this.capturedPieces = new ArrayList<>();
    }

    MoveUndo(int movedPiece, int oldKingRow, int oldKingCol) {
        this(movedPiece, oldKingRow, oldKingCol, 0L);
    }

    void addCapturedPiece(int row, int col, int piece) {
        capturedPieces.add(new CapturedPiece(row, col, piece));
    }
}
