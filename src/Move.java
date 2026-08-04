public class Move {
    int fromRow, fromCol, toRow, toCol;
    int sortScore;   // note de tri temporaire utilisee par orderMoves

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
        int row = Board.SIZE - Integer.parseInt(sq.substring(1));

        return new int[]{row, col};
    }

    static String toSquare(int row, int col){
        char letterCol = (char)('A' + col);
        int numberRow = Board.SIZE - row;

        return "" + letterCol + numberRow;
    }

    public String toString() {
        return toSquare(fromRow, fromCol) + "-" + toSquare(toRow, toCol);
    }
}