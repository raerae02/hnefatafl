public class Move {
    final int fromRow, fromCol, toRow, toCol;

    Move(int fromRow, int fromCol, int toRow, int toCol) {
        this.fromRow = fromRow;
        this.fromCol = fromCol;
        this.toRow = toRow;
        this.toCol = toCol;
    }

    // Convertit un texte comme "G7-H7" en Move.
    // Retourne null si le coup est invalide (ex. "A0-A0" envoye par le serveur).
    static Move tryParse(String s) {
        try {
            s = s.replace(" ", "").trim();
            String[] parts = s.split("-");
            int[] from = parseSquare(parts[0]);
            int[] to = parseSquare(parts[1]);
            Move move = new Move(from[0], from[1], to[0], to[1]);

            if (move.fromRow < 0 || move.fromRow > 12 || move.fromCol < 0 || move.fromCol > 12
                    || move.toRow < 0 || move.toRow > 12 || move.toCol < 0 || move.toCol > 12) {
                return null;
            }
            return move;
        } catch (Exception e) {
            return null;
        }
    }

    static int[] parseSquare(String sq){
        int col = sq.charAt(0) - 'A';
        int row = 13 - Integer.parseInt(sq.substring(1));

        return new int[]{row, col};
    }

    static String toSquare(int row, int col){
        char letterCol = (char)('A' + col);
        int numberRow = 13 - row;

        return "" + letterCol + numberRow;
    }

    public String toString() {
        return toSquare(fromRow, fromCol) + "-" + toSquare(toRow, toCol);
    }

    int encode() {
        return 1 + fromRow
                + (fromCol << 4)
                + (toRow << 8)
                + (toCol << 12);
    }

    static Move decode(int encoded) {
        if (encoded <= 0) return null;
        int packed = encoded - 1;
        return new Move(
                packed & 0xF,
                (packed >>> 4) & 0xF,
                (packed >>> 8) & 0xF,
                (packed >>> 12) & 0xF);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Move)) return false;

        Move move = (Move) other;
        return fromRow == move.fromRow
                && fromCol == move.fromCol
                && toRow == move.toRow
                && toCol == move.toCol;
    }

    @Override
    public int hashCode() {
        int result = fromRow;
        result = 31 * result + fromCol;
        result = 31 * result + toRow;
        result = 31 * result + toCol;
        return result;
    }
}
