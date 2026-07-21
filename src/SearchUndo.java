/** Reusable allocation-free undo state for the search stack. */
final class SearchUndo {
    int movedPiece;
    int oldKingRow;
    int oldKingCol;
    long oldHash;
    int capturedCount;
    final int[] capturedSquares = new int[4];
    final int[] capturedPieces = new int[4];

    void reset(int movedPiece, int oldKingRow, int oldKingCol, long oldHash) {
        this.movedPiece = movedPiece;
        this.oldKingRow = oldKingRow;
        this.oldKingCol = oldKingCol;
        this.oldHash = oldHash;
        capturedCount = 0;
    }

    void capture(int square, int piece) {
        capturedSquares[capturedCount] = square;
        capturedPieces[capturedCount++] = piece;
    }
}
