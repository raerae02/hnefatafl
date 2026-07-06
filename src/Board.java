import java.util.ArrayList;
import java.util.List;

class Board {
    private int kingRow, kingCol;
    static final int EMPTY = 0, BLACK = 2, RED = 4, KING = 5;
    int[][] grid;

    public Board(int[][] grid){
        this.grid = grid;
        for (int i = 0; i < 13; i++) {
            for (int j = 0; j < 13; j++) {
                if(grid[i][j] == KING){
                    kingRow = i;
                    kingCol = j;
                }
            }
        }
    }

    // apply moves and captures
    public void applyMove(Move m) {
        int piece = grid[m.fromRow][m.fromCol];
        grid[m.toRow][m.toCol] = grid[m.fromRow][m.fromCol];
        grid[m.fromRow][m.fromCol] = EMPTY;

        if (piece == KING) {
            kingRow = m.toRow;
            kingCol = m.toCol;
        }

        if (inBounds(m.toRow-1, m.toCol)) {
            int victim = grid[m.toRow-1][m.toCol];

            if (victim != EMPTY && !sameTeam(victim, piece) && victim != KING) {
                boolean captured = false;
                if (inBounds(m.toRow-2, m.toCol)) {
                    int backup = grid[m.toRow-2][m.toCol];
                    captured = sameTeam(backup, piece) || (backup == EMPTY && isHostileSquare(m.toRow-2, m.toCol));
                }
                if (captured) grid[m.toRow-1][m.toCol] = EMPTY;
            }
        }
        if (inBounds(m.toRow+1, m.toCol)) {
            int victim = grid[m.toRow+1][m.toCol];

            if (victim != EMPTY && !sameTeam(victim, piece) && victim != KING) {
                boolean captured = false;
                if (inBounds(m.toRow+2, m.toCol)) {
                    int backup = grid[m.toRow+2][m.toCol];
                    captured = sameTeam(backup, piece) || (backup == EMPTY && isHostileSquare(m.toRow+2, m.toCol));
                }
                if (captured) grid[m.toRow+1][m.toCol] = EMPTY;
            }
        }

        if (inBounds(m.toRow, m.toCol-1)) {
            int victim = grid[m.toRow][m.toCol-1];

            if (victim != EMPTY && !sameTeam(victim, piece) && victim != KING) {
                boolean captured = false;
                if (inBounds(m.toRow, m.toCol-2)) {
                    int backup = grid[m.toRow][m.toCol-2];
                    captured = sameTeam(backup, piece) || (backup == EMPTY && isHostileSquare(m.toRow, m.toCol-2));
                }
                if (captured) grid[m.toRow][m.toCol-1] = EMPTY;
            }
        }

        if (inBounds(m.toRow, m.toCol+1)) {
            int victim = grid[m.toRow][m.toCol+1];

            if (victim != EMPTY && !sameTeam(victim, piece) && victim != KING) {
                boolean captured = false;
                if (inBounds(m.toRow, m.toCol+2)) {
                    int backup = grid[m.toRow][m.toCol+2];
                    captured = sameTeam(backup, piece) || (backup == EMPTY && isHostileSquare(m.toRow, m.toCol+2));
                }
                if (captured) grid[m.toRow][m.toCol+1] = EMPTY;
            }
        }
    }

    // tous les coups valides pour rouge ou noir
    public List<Move> getLegalMoves(int player) {
        List<Move> moves = new ArrayList<>();
        for (int fr = 0; fr < 13; fr++) {
            for (int fc = 0; fc < 13; fc++) {
                int piece = grid[fr][fc];
                boolean mine = (isAttacker(player)) ? (piece == RED) : (piece == BLACK || piece == KING); // a king is a black piece
                if(!mine) continue;
                for (int tc = 0; tc < 13; tc++) {
                    Move m = new Move(fr, fc, fr, tc);
                    if (isValidMove(m)) moves.add(m);
                }
                for (int tr = 0; tr < 13; tr++) {
                    Move m = new Move(fr, fc, tr, fc);
                    if (isValidMove(m)) moves.add(m);
                }
            }
        }
        return moves;
    }

    public int miniMax(Board b){
        return -1;
    }

    public boolean isTerminal() { return getWinner() != 0; }

    public int getWinner() {
        // black win : king is in the corner
        if (isCorner(kingRow, kingCol)) return BLACK;

        // red win : king is blocked from all 4 sides
        if (isBlockedForKing(kingRow-1, kingCol) &&
                isBlockedForKing(kingRow+1, kingCol) &&
                isBlockedForKing(kingRow, kingCol-1) &&
                isBlockedForKing(kingRow, kingCol+1)) return RED;

        return 0;
    }

    public void print() {
        char[] symbols = {'.', '?', 'N', '?', 'R', 'K'};
        for (int r = 0; r < 13; r++) {
            System.out.printf("%2d  ", 13 - r);
            for (int c = 0; c < 13; c++) {
                System.out.print(symbols[grid[r][c]] + " ");
            }
            System.out.println();
        }
        System.out.println("    A B C D E F G H I J K L M");
    }

    private boolean isValidMove(Move m){
        int piece = grid[m.fromRow][m.fromCol];

        if(piece == EMPTY) return false;

        if(grid[m.toRow][m.toCol] != EMPTY) return false;

        boolean isThrone = isThrone(m.toRow, m.toCol);
        boolean isCorner = isCorner(m.toRow, m.toCol);

        // king rules
        if ((isThrone || isCorner) && piece != KING) return false;

        // not a diagonal move
        if (m.fromRow != m.toRow && m.fromCol != m.toCol) return false;

        // the piece actually move
        if(m.fromRow == m.toRow && m.fromCol == m.toCol) return false;

        return isPathClear(m);
    }

    private boolean isCorner(int row, int col) {
        return (row == 0 || row == 12) && (col == 0 || col == 12);
    }

    private boolean isThrone(int row, int col){
        return row == 6 && col == 6;
    }

    private boolean isPathClear(Move m){
        int rowDirection = Integer.signum(m.toRow - m.fromRow);
        int colDirection = Integer.signum(m.toCol - m.fromCol);

        int row = m.fromRow + rowDirection;
        int col = m.fromCol + colDirection;

        while (row != m.toRow || col != m.toCol){
            if(grid[row][col] != EMPTY) return false;
            row += rowDirection;
            col += colDirection;
        }

        return true;
    }

    private boolean isDefender(int p) { return p == BLACK || p == KING; }

    private boolean isAttacker(int p) { return p == RED; }

    private boolean sameTeam(int a, int b) {
        return (isAttacker(a) && isAttacker(b)) || (isDefender(a) && isDefender(b));
    }

    private boolean isHostileSquare(int row, int col) {
        boolean corner = (row == 0 || row == 12) && (col == 0 || col == 12);
        boolean throne = (row == 6 && col == 6);
        return corner || throne;
    }

    private boolean inBounds(int row, int col) {
        return row >= 0 && row < 13 && col >= 0 && col < 13;
    }

    private boolean isBlockedForKing(int row, int col) {
        if (!inBounds(row, col)) return true;
        if (isCorner(row, col)) return true;
        if (isThrone(row, col)) return true;
        return grid[row][col] == RED;
    }
}