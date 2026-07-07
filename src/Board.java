import java.util.ArrayList;
import java.util.List;
import java.util.Random;

class Board {
    static final int BOARD_SIZE = 13;
    static final int THRONE_ROW = 6;
    static final int THRONE_COL = 6;
    static final int[][] DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    static final int[][] CORNERS = {
            {0, 0},
            {0, BOARD_SIZE - 1},
            {BOARD_SIZE - 1, 0},
            {BOARD_SIZE - 1, BOARD_SIZE - 1}
    };

    private int kingRow, kingCol;
    static final int EMPTY = 0, BLACK = 2, RED = 4, KING = 5;
    private static final int HASH_PIECES = 3;
    private static final long[][][] ZOBRIST_TABLE = new long[BOARD_SIZE][BOARD_SIZE][HASH_PIECES];
    private static boolean zobristInitialized = false;
    int[][] grid;

    public int getKingRow() {
        return kingRow;
    }
    public int getKingCol() {
        return kingCol;
    }

    public int getPiece(int row, int col) {
        return grid[row][col];
    }

    public int getOpenKingLines() {
        return countOpenKingLines();
    }



    public Board(int[][] grid){
        this.grid = grid;
        for (int i = 0; i < BOARD_SIZE; i++) {
            for (int j = 0; j < BOARD_SIZE; j++) {
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
        movePiece(m, piece);
        captureAround(m.toRow, m.toCol, piece, null);
    }

    private void captureAround(int row, int col, int piece, MoveUndo undo) {
        for (int[] direction : DIRECTIONS) {
            int victimRow = row + direction[0];
            int victimCol = col + direction[1];
            int backupRow = row + 2 * direction[0];
            int backupCol = col + 2 * direction[1];

            if (!inBounds(victimRow, victimCol)) continue;

            int victim = grid[victimRow][victimCol];
            if (victim == EMPTY || victim == KING || sameTeam(victim, piece)) continue;

            if (shouldCapture(backupRow, backupCol, piece)) {
                if (undo != null) {
                    undo.addCapturedPiece(victimRow, victimCol, victim);
                }
                grid[victimRow][victimCol] = EMPTY;
            }
        }
    }

    private boolean shouldCapture(int backupRow, int backupCol, int piece) {
        if (!inBounds(backupRow, backupCol)) return false;

        int backup = grid[backupRow][backupCol];
        return backup == KING || sameTeam(backup, piece) || (backup == EMPTY && isHostileSquare(backupRow, backupCol));
    }

    public MoveUndo makeMove(Move move) {
        int piece = grid[move.fromRow][move.fromCol];
        MoveUndo undo = new MoveUndo(piece, kingRow, kingCol);

        movePiece(move, piece);
        captureAround(move.toRow, move.toCol, piece, undo);
        return undo;
    }

    private void movePiece(Move move, int piece) {
        grid[move.toRow][move.toCol] = piece;
        grid[move.fromRow][move.fromCol] = EMPTY;

        if (piece == KING) {
            kingRow = move.toRow;
            kingCol = move.toCol;
        }
    }

    public void unmakeMove(Move move, MoveUndo undo) {
        grid[move.fromRow][move.fromCol] = undo.movedPiece;
        grid[move.toRow][move.toCol] = EMPTY;

        kingRow = undo.oldKingRow;
        kingCol = undo.oldKingCol;

        for (CapturedPiece capturedPiece : undo.capturedPieces) {
            grid[capturedPiece.row][capturedPiece.col] = capturedPiece.piece;
        }
    }

    // tous les coups valides pour rouge ou noir
    public List<Move> getLegalMoves(int player) {
        List<Move> moves = new ArrayList<>();

        for (int fr = 0; fr < BOARD_SIZE; fr++) {
            for (int fc = 0; fc < BOARD_SIZE; fc++) {
                int piece = grid[fr][fc];
                boolean mine = (isAttacker(player)) ? (piece == RED) : (piece == BLACK || piece == KING); // a king is a black piece
                if(!mine) continue;

                for (int[] direction : DIRECTIONS) {
                    int tr = fr + direction[0];
                    int tc = fc + direction[1];

                    while (inBounds(tr, tc) && grid[tr][tc] == EMPTY) {
                        if (piece == KING || (!isThrone(tr, tc) && !isCorner(tr, tc))) {
                            moves.add(new Move(fr, fc, tr, tc));
                        }

                        tr += direction[0];
                        tc += direction[1];
                    }
                }
            }
        }
        return moves;
    }

    public Board copy() {
        int[][] newGrid = new int[BOARD_SIZE][BOARD_SIZE];
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                newGrid[r][c] = grid[r][c];
            }
        }
        return new Board(newGrid);
    }

    public int evaluate(int cpuPlayer) {
        return new BoardEvaluator(this).evaluate(cpuPlayer);
    }

    public int countCapturesIfMove(Move move) {
        int piece = grid[move.fromRow][move.fromCol];
        int captures = 0;

        for (int[] direction : DIRECTIONS) {
            int victimRow = move.toRow + direction[0];
            int victimCol = move.toCol + direction[1];
            int backupRow = move.toRow + 2 * direction[0];
            int backupCol = move.toCol + 2 * direction[1];

            if (!inBounds(victimRow, victimCol)) continue;

            int victim = getPieceAfterMove(victimRow, victimCol, move, piece);
            if (victim == EMPTY || victim == KING || sameTeam(victim, piece)) continue;

            if (shouldCaptureAfterMove(backupRow, backupCol, move, piece)) {
                captures++;
            }
        }

        return captures;
    }

    private boolean shouldCaptureAfterMove(int backupRow, int backupCol, Move move, int piece) {
        if (!inBounds(backupRow, backupCol)) return false;

        int backup = getPieceAfterMove(backupRow, backupCol, move, piece);
        return backup == KING || sameTeam(backup, piece) || (backup == EMPTY && isHostileSquare(backupRow, backupCol));
    }

    private int getPieceAfterMove(int row, int col, Move move, int piece) {
        if (row == move.fromRow && col == move.fromCol) {
            return EMPTY;
        }
        if (row == move.toRow && col == move.toCol) {
            return piece;
        }
        return grid[row][col];
    }

    public boolean isTerminal() { return getWinner() != 0; }

    public int getWinner() {
      if (isCorner(kingRow, kingCol)) return BLACK;
      if (isKingCaptured()) return RED;
      return 0;
    }

    private boolean isKingCaptured(){
        int hostileSides = countHostileSidesAroundKing();

        if (hostileSides == 4) {
            return true;
        }
        if (isKingOnBoardEdge() && hostileSides >= 3){
            return true;
        }
        if (isKingAdjacentToThrone() && hostileSides >= 3){
            return true;
        }
        return false;
    }

    private int countHostileSidesAroundKing(){
        int count = 0;

        for (int[] direction : DIRECTIONS) {
            int row = kingRow + direction[0];
            int col = kingCol + direction[1];

            if (isHostileToKing(row, col)) {
                count++;
            }
        }
        return count;
    }
    private boolean isHostileToKing(int row, int col) {
        if (!inBounds(row, col)) return true;
        if (grid[row][col] == RED) return true;
        if (isThrone(row, col)) return true;
        if (isCorner(row, col)) return true;
        return false;
    }
    private boolean isKingOnBoardEdge() {
        return kingRow == 0 || kingRow == BOARD_SIZE - 1 || kingCol == 0 || kingCol == BOARD_SIZE - 1;
    }

    private boolean isKingAdjacentToThrone(){
        return distanceManhattan(kingRow, kingCol, THRONE_ROW, THRONE_COL) == 1;
    }
    private int distanceManhattan(int r1, int c1, int r2, int c2) {
        return Math.abs(r1 - r2) + Math.abs(c1 - c2);
    }

    public void print() {
        char[] symbols = {'.', '?', 'N', '?', 'R', 'K'};
        for (int r = 0; r < BOARD_SIZE; r++) {
            System.out.printf("%2d  ", BOARD_SIZE - r);
            for (int c = 0; c < BOARD_SIZE; c++) {
                System.out.print(symbols[grid[r][c]] + " ");
            }
            System.out.println();
        }
        System.out.println("    A B C D E F G H I J K L M");
    }

    public boolean isValidMove(Move m){
        if (!inBounds(m.fromRow, m.fromCol) || !inBounds(m.toRow, m.toCol)) return false;

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
        return (row == 0 || row == BOARD_SIZE - 1) && (col == 0 || col == BOARD_SIZE - 1);
    }

    private boolean isThrone(int row, int col){
        return row == THRONE_ROW && col == THRONE_COL;
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
        boolean corner = (row == 0 || row == BOARD_SIZE - 1) && (col == 0 || col == BOARD_SIZE - 1);
        boolean throne = (row == THRONE_ROW && col == THRONE_COL);
        return corner || throne;
    }

    private boolean inBounds(int row, int col) {
        return row >= 0 && row < BOARD_SIZE && col >= 0 && col < BOARD_SIZE;
    }

    private int countOpenKingLines() {
        int openLines = 0;

        for (int[] direction : DIRECTIONS) {
            if (isPathOpenForKing(kingRow, kingCol, direction[0], direction[1])) {
                openLines++;
            }
        }

        return openLines;
    }

    private boolean isPathOpenForKing(int row, int col, int dr, int dc) {
        int r = row + dr;
        int c = col + dc;

        while (inBounds(r, c)) {
            if (grid[r][c] != EMPTY) return false;
            if (isCorner(r, c)) return true;
            r += dr;
            c += dc;
        }

        return false;
    }

    public int countPieces(int player){
        int count = 0;

        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                int piece =  grid[row][col];

                if(player == Board.RED){
                    if(piece == Board.RED){
                        count++;
                    }
                }else if(player == Board.BLACK){
                    if(piece == Board.BLACK || piece == Board.KING){
                        count++;
                    }
                }
            }

        }
        return count;

    }

    public long computeHash() {
        initZobrist();

        long hash = 0L;
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                int piece = grid[row][col];
                if (piece != EMPTY) {
                    hash ^= ZOBRIST_TABLE[row][col][pieceToHashIndex(piece)];
                }
            }
        }

        return hash;
    }

    public String positionKey() {
        StringBuilder builder = new StringBuilder(BOARD_SIZE * BOARD_SIZE * 2);

        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                builder.append(grid[row][col]);
                builder.append(',');
            }
        }

        return builder.toString();
    }

    private static void initZobrist() {
        if (zobristInitialized) return;

        Random random = new Random(3202024L);
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                for (int piece = 0; piece < HASH_PIECES; piece++) {
                    ZOBRIST_TABLE[row][col][piece] = random.nextLong();
                }
            }
        }

        zobristInitialized = true;
    }

    private int pieceToHashIndex(int piece) {
        if (piece == RED) return 0;
        if (piece == BLACK) return 1;
        if (piece == KING) return 2;
        throw new IllegalArgumentException("Piece inconnue pour le hash: " + piece);
    }
}
