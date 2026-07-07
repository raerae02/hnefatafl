import java.util.ArrayList;
import java.util.List;

class MoveUndo {
    final int movedPiece;
    final int oldKingRow;
    final int oldKingCol;
    final List<CapturedPiece> capturedPieces;

    MoveUndo(int movedPiece, int oldKingRow, int oldKingCol) {
        this.movedPiece = movedPiece;
        this.oldKingRow = oldKingRow;
        this.oldKingCol = oldKingCol;
        this.capturedPieces = new ArrayList<>();
    }

    void addCapturedPiece(int row, int col, int piece) {
        capturedPieces.add(new CapturedPiece(row, col, piece));
    }
}
