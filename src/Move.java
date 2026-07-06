public class Move {
    int fromRow, fromCol, toRow, toCol;

    Move(int fromRow, int fromCol, int toRow, int toCol) {
        this.fromRow = fromRow;
        this.fromCol = fromCol;
        this.toRow = toRow;
        this.toCol = toCol;
    }

    static Move parse(String s) {
        s = s.replace(" ", "").trim();
        String[] parts = s.split("-");
        int[] from = parseSquare(parts[0]);
        int[] to = parseSquare(parts[1]);
        return new Move(from[0], from[1], to[0], to[1]);
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
}