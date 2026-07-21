public class Move {
    final int fromRow;
    final int fromCol;
    final int toRow;
    final int toCol;

    Move(int fromRow, int fromCol, int toRow, int toCol) {
        this.fromRow = fromRow;
        this.fromCol = fromCol;
        this.toRow = toRow;
        this.toCol = toCol;
    }

    static Move parse(String s) {
        s = s.replace(" ", "").trim();
        String fromSquare;
        String toSquare;

        if (s.contains("-")) {
            String[] parts = s.split("-");
            fromSquare = parts[0];
            toSquare = parts[1];
        } else {
            int splitIndex = findSecondSquareIndex(s);
            fromSquare = s.substring(0, splitIndex);
            toSquare = s.substring(splitIndex);
        }

        int[] from = parseSquare(fromSquare);
        int[] to = parseSquare(toSquare);
        return new Move(from[0], from[1], to[0], to[1]);
    }

    private static int findSecondSquareIndex(String s) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) >= 'A' && s.charAt(i) <= 'M') {
                return i;
            }
        }
        throw new IllegalArgumentException("Invalid move: " + s);
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

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Move move)) return false;
        return fromRow == move.fromRow && fromCol == move.fromCol
                && toRow == move.toRow && toCol == move.toCol;
    }

    @Override
    public int hashCode() {
        return PackedMove.pack(this);
    }
}
