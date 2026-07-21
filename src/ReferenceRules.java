import java.util.ArrayList;
import java.util.List;

/** Deliberately simple grid implementation used only as an optimization parity oracle. */
final class ReferenceRules {
    private ReferenceRules() {}

    static List<Move> legalMoves(int[][] grid, int player) {
        List<Move> moves = new ArrayList<>();
        for (int row = 0; row < Board.BOARD_SIZE; row++) for (int col = 0; col < Board.BOARD_SIZE; col++) {
            int piece = grid[row][col];
            if (player == Board.RED ? piece != Board.RED : piece != Board.BLACK && piece != Board.KING) continue;
            for (int[] direction : Board.DIRECTIONS) {
                int r = row + direction[0], c = col + direction[1];
                while (inBounds(r, c) && grid[r][c] == Board.EMPTY) {
                    if (piece == Board.KING || (!corner(r, c) && !throne(r, c)))
                        moves.add(new Move(row, col, r, c));
                    r += direction[0]; c += direction[1];
                }
            }
        }
        return moves;
    }

    static int[][] apply(int[][] source, Move move) {
        int[][] grid = copy(source);
        int piece = grid[move.fromRow][move.fromCol];
        grid[move.fromRow][move.fromCol] = Board.EMPTY;
        grid[move.toRow][move.toCol] = piece;
        for (int[] direction : Board.DIRECTIONS) {
            int vr = move.toRow + direction[0], vc = move.toCol + direction[1];
            int br = move.toRow + 2 * direction[0], bc = move.toCol + 2 * direction[1];
            if (!inBounds(vr, vc) || !inBounds(br, bc)) continue;
            int victim = grid[vr][vc], backup = grid[br][bc];
            if (victim == Board.EMPTY || victim == Board.KING || sameTeam(victim, piece)) continue;
            if (backup == Board.KING || sameTeam(backup, piece)
                    || (backup == Board.EMPTY && (corner(br, bc) || throne(br, bc))))
                grid[vr][vc] = Board.EMPTY;
        }
        return grid;
    }

    static int winner(int[][] grid) {
        int kingRow = -1, kingCol = -1;
        for (int r = 0; r < Board.BOARD_SIZE; r++) for (int c = 0; c < Board.BOARD_SIZE; c++)
            if (grid[r][c] == Board.KING) { kingRow = r; kingCol = c; }
        if (kingRow < 0 || corner(kingRow, kingCol)) return kingRow < 0 ? Board.RED : Board.BLACK;
        int hostile = 0;
        for (int[] d : Board.DIRECTIONS) {
            int r = kingRow + d[0], c = kingCol + d[1];
            if (!inBounds(r, c) || grid[r][c] == Board.RED || throne(r, c) || corner(r, c)) hostile++;
        }
        boolean edge = kingRow == 0 || kingRow == 12 || kingCol == 0 || kingCol == 12;
        boolean besideThrone = Math.abs(kingRow - Board.THRONE_ROW) + Math.abs(kingCol - Board.THRONE_COL) == 1;
        return hostile == 4 || ((edge || besideThrone) && hostile >= 3) ? Board.RED : 0;
    }

    static int[][] copy(int[][] source) {
        int[][] copy = new int[Board.BOARD_SIZE][Board.BOARD_SIZE];
        for (int r = 0; r < Board.BOARD_SIZE; r++) System.arraycopy(source[r], 0, copy[r], 0, Board.BOARD_SIZE);
        return copy;
    }

    private static boolean sameTeam(int a, int b) {
        return a == Board.RED && b == Board.RED
                || (a == Board.BLACK || a == Board.KING) && (b == Board.BLACK || b == Board.KING);
    }
    private static boolean inBounds(int r, int c) { return r >= 0 && r < 13 && c >= 0 && c < 13; }
    private static boolean corner(int r, int c) { return (r == 0 || r == 12) && (c == 0 || c == 12); }
    private static boolean throne(int r, int c) { return r == 6 && c == 6; }
}
