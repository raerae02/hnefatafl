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

    private int kingRow = -1, kingCol = -1;
    static final int EMPTY = 0, BLACK = 2, RED = 4, KING = 5;
    private static final int HASH_PIECES = 3;
    private static final long[][][] ZOBRIST_TABLE = new long[BOARD_SIZE][BOARD_SIZE][HASH_PIECES];
    private static boolean zobristInitialized = false;
    /** Compatibility view for the client and legacy tests; bitboards are authoritative. */
    final int[][] grid = new int[BOARD_SIZE][BOARD_SIZE];
    private long redLow, redMiddle, redHigh;
    private long blackLow, blackMiddle, blackHigh;
    private long kingLow, kingMiddle, kingHigh;
    private long zobristHash;
    private final int[] evaluationMoveBuffer = new int[4096];
    private final int[] evaluationReplyBuffer = new int[4096];
    private final SearchUndo evaluationPrimaryUndo = new SearchUndo();
    private final SearchUndo evaluationReplyUndo = new SearchUndo();
    private static final int[][][] RAYS = buildRays();

    public int getKingRow() {
        return kingRow;
    }
    public int getKingCol() {
        return kingCol;
    }

    public int getPiece(int row, int col) {
        int square = BitBoard169.square(row, col);
        if (containsKing(square)) return KING;
        if (containsRed(square)) return RED;
        if (containsBlack(square)) return BLACK;
        return EMPTY;
    }

    public int getOpenKingLines() {
        return countOpenKingLines();
    }



    public Board(int[][] source){
        initZobrist();
        for (int i = 0; i < BOARD_SIZE; i++) {
            for (int j = 0; j < BOARD_SIZE; j++) {
                int piece = source[i][j];
                grid[i][j] = piece;
                if (piece != EMPTY) {
                    int square = BitBoard169.square(i, j);
                    addPiece(square, piece);
                    zobristHash ^= ZOBRIST_TABLE[i][j][pieceToHashIndex(piece)];
                }
                if(piece == KING){
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
                removePiece(BitBoard169.square(victimRow, victimCol), victim);
                grid[victimRow][victimCol] = EMPTY;
                zobristHash ^= ZOBRIST_TABLE[victimRow][victimCol][pieceToHashIndex(victim)];
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
        MoveUndo undo = new MoveUndo(piece, kingRow, kingCol, zobristHash);

        movePiece(move, piece);
        captureAround(move.toRow, move.toCol, piece, undo);
        return undo;
    }

    void makeMovePacked(int packed, SearchUndo undo) {
        int from = PackedMove.from(packed), to = PackedMove.to(packed);
        int fromRow = BitBoard169.row(from), fromCol = BitBoard169.col(from);
        int toRow = BitBoard169.row(to), toCol = BitBoard169.col(to);
        int piece = getPiece(fromRow, fromCol);
        undo.reset(piece, kingRow, kingCol, zobristHash);
        movePieceSquares(from, to, fromRow, fromCol, toRow, toCol, piece);
        captureAroundPacked(toRow, toCol, piece, undo);
    }

    void unmakeMovePacked(int packed, SearchUndo undo) {
        int from = PackedMove.from(packed), to = PackedMove.to(packed);
        int fromRow = BitBoard169.row(from), fromCol = BitBoard169.col(from);
        int toRow = BitBoard169.row(to), toCol = BitBoard169.col(to);
        removePiece(to, undo.movedPiece);
        addPiece(from, undo.movedPiece);
        grid[fromRow][fromCol] = undo.movedPiece;
        grid[toRow][toCol] = EMPTY;
        kingRow = undo.oldKingRow;
        kingCol = undo.oldKingCol;
        for (int i = 0; i < undo.capturedCount; i++) {
            int square = undo.capturedSquares[i], piece = undo.capturedPieces[i];
            addPiece(square, piece);
            grid[BitBoard169.row(square)][BitBoard169.col(square)] = piece;
        }
        zobristHash = undo.oldHash;
    }

    private void captureAroundPacked(int row, int col, int piece, SearchUndo undo) {
        for (int[] direction : DIRECTIONS) {
            int victimRow = row + direction[0], victimCol = col + direction[1];
            int backupRow = row + 2 * direction[0], backupCol = col + 2 * direction[1];
            if (!inBounds(victimRow, victimCol)) continue;
            int victim = getPiece(victimRow, victimCol);
            if (victim == EMPTY || victim == KING || sameTeam(victim, piece)) continue;
            if (shouldCapture(backupRow, backupCol, piece)) {
                int square = BitBoard169.square(victimRow, victimCol);
                undo.capture(square, victim);
                removePiece(square, victim);
                grid[victimRow][victimCol] = EMPTY;
                zobristHash ^= ZOBRIST_TABLE[victimRow][victimCol][pieceToHashIndex(victim)];
            }
        }
    }

    private void movePiece(Move move, int piece) {
        int from = BitBoard169.square(move.fromRow, move.fromCol);
        int to = BitBoard169.square(move.toRow, move.toCol);
        movePieceSquares(from, to, move.fromRow, move.fromCol, move.toRow, move.toCol, piece);
    }

    private void movePieceSquares(int from, int to, int fromRow, int fromCol,
                                  int toRow, int toCol, int piece) {
        removePiece(from, piece);
        addPiece(to, piece);
        zobristHash ^= ZOBRIST_TABLE[fromRow][fromCol][pieceToHashIndex(piece)];
        zobristHash ^= ZOBRIST_TABLE[toRow][toCol][pieceToHashIndex(piece)];
        grid[toRow][toCol] = piece;
        grid[fromRow][fromCol] = EMPTY;

        if (piece == KING) {
            kingRow = toRow;
            kingCol = toCol;
        }
    }

    public void unmakeMove(Move move, MoveUndo undo) {
        removePiece(BitBoard169.square(move.toRow, move.toCol), undo.movedPiece);
        addPiece(BitBoard169.square(move.fromRow, move.fromCol), undo.movedPiece);
        grid[move.fromRow][move.fromCol] = undo.movedPiece;
        grid[move.toRow][move.toCol] = EMPTY;

        kingRow = undo.oldKingRow;
        kingCol = undo.oldKingCol;

        for (CapturedPiece capturedPiece : undo.capturedPieces) {
            addPiece(BitBoard169.square(capturedPiece.row, capturedPiece.col), capturedPiece.piece);
            grid[capturedPiece.row][capturedPiece.col] = capturedPiece.piece;
        }
        zobristHash = undo.oldHash;
    }

    // tous les coups valides pour rouge ou noir
    public List<Move> getLegalMoves(int player) {
        List<Move> moves = new ArrayList<>();
        int[] packed = new int[4096];
        int count = getLegalPackedMoves(player, packed);
        for (int i = 0; i < count; i++) moves.add(PackedMove.unpack(packed[i]));
        return moves;
    }

    int getLegalPackedMoves(int player, int[] moves) {
        int count = 0;
        for (int square = 0; square < BOARD_SIZE * BOARD_SIZE; square++) {
            int piece = getPiece(BitBoard169.row(square), BitBoard169.col(square));
            boolean mine = player == RED ? piece == RED : piece == BLACK || piece == KING;
            if (!mine) continue;

            for (int direction = 0; direction < DIRECTIONS.length; direction++) {
                for (int target : RAYS[square][direction]) {
                    if (isOccupied(target)) break;
                    int row = BitBoard169.row(target);
                    int col = BitBoard169.col(target);
                    if (piece == KING || (!BoardGeometry.THRONE.contains(target)
                            && !BoardGeometry.CORNERS.contains(target))) {
                        if (count >= moves.length) throw new IllegalArgumentException("Move buffer too small");
                        moves[count++] = PackedMove.pack(square, target);
                    }
                }
            }
        }
        return count;
    }

    int[] evaluationMoveBuffer() { return evaluationMoveBuffer; }
    int[] evaluationReplyBuffer() { return evaluationReplyBuffer; }
    SearchUndo evaluationPrimaryUndo() { return evaluationPrimaryUndo; }
    SearchUndo evaluationReplyUndo() { return evaluationReplyUndo; }

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

    int countCapturesIfPacked(int packed) {
        int from = PackedMove.from(packed);
        int to = PackedMove.to(packed);
        int fromRow = BitBoard169.row(from), fromCol = BitBoard169.col(from);
        int toRow = BitBoard169.row(to), toCol = BitBoard169.col(to);
        int piece = getPiece(fromRow, fromCol);
        int captures = 0;
        for (int[] direction : DIRECTIONS) {
            int victimRow = toRow + direction[0], victimCol = toCol + direction[1];
            int backupRow = toRow + 2 * direction[0], backupCol = toCol + 2 * direction[1];
            if (!inBounds(victimRow, victimCol) || !inBounds(backupRow, backupCol)) continue;
            int victim = getPieceAfterPacked(victimRow, victimCol, fromRow, fromCol, toRow, toCol, piece);
            if (victim == EMPTY || victim == KING || sameTeam(victim, piece)) continue;
            int backup = getPieceAfterPacked(backupRow, backupCol, fromRow, fromCol, toRow, toCol, piece);
            if (backup == KING || sameTeam(backup, piece)
                    || (backup == EMPTY && isHostileSquare(backupRow, backupCol))) captures++;
        }
        return captures;
    }

    private int getPieceAfterPacked(int row, int col, int fromRow, int fromCol,
                                    int toRow, int toCol, int piece) {
        if (row == fromRow && col == fromCol) return EMPTY;
        if (row == toRow && col == toCol) return piece;
        return getPiece(row, col);
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
      if (kingRow < 0 || kingCol < 0) return RED;
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
        if (player == RED) return BitBoard169.count(redLow, redMiddle, redHigh);
        return BitBoard169.count(blackLow, blackMiddle, blackHigh)
                + BitBoard169.count(kingLow, kingMiddle, kingHigh);

    }

    public long computeHash() {
        return zobristHash;
    }

    public String positionKey() {
        return Long.toHexString(redLow) + ':' + Long.toHexString(redMiddle) + ':' + Long.toHexString(redHigh)
                + ':' + Long.toHexString(blackLow) + ':' + Long.toHexString(blackMiddle) + ':' + Long.toHexString(blackHigh)
                + ':' + Long.toHexString(kingLow) + ':' + Long.toHexString(kingMiddle) + ':' + Long.toHexString(kingHigh);
    }

    public String positionKey(int sideToMove) {
        return positionKey() + ':' + sideToMove;
    }

    PositionKey exactKey(int sideToMove) {
        return new PositionKey(redLow, redMiddle, redHigh, blackLow, blackMiddle, blackHigh,
                kingLow, kingMiddle, kingHigh, sideToMove);
    }

    private boolean containsRed(int square) { return BitBoard169.contains(redLow, redMiddle, redHigh, square); }
    private boolean containsBlack(int square) { return BitBoard169.contains(blackLow, blackMiddle, blackHigh, square); }
    private boolean containsKing(int square) { return BitBoard169.contains(kingLow, kingMiddle, kingHigh, square); }
    private boolean isOccupied(int square) { return containsRed(square) || containsBlack(square) || containsKing(square); }

    private void addPiece(int square, int piece) {
        setPieceBit(square, piece, true);
    }

    private void removePiece(int square, int piece) {
        setPieceBit(square, piece, false);
    }

    private void setPieceBit(int square, int piece, boolean present) {
        int segment = square >>> 6;
        long bit = 1L << (square & 63);
        if (piece == RED) {
            if (segment == 0) redLow = present ? redLow | bit : redLow & ~bit;
            else if (segment == 1) redMiddle = present ? redMiddle | bit : redMiddle & ~bit;
            else redHigh = present ? redHigh | bit : redHigh & ~bit;
        } else if (piece == BLACK) {
            if (segment == 0) blackLow = present ? blackLow | bit : blackLow & ~bit;
            else if (segment == 1) blackMiddle = present ? blackMiddle | bit : blackMiddle & ~bit;
            else blackHigh = present ? blackHigh | bit : blackHigh & ~bit;
        } else if (piece == KING) {
            if (segment == 0) kingLow = present ? kingLow | bit : kingLow & ~bit;
            else if (segment == 1) kingMiddle = present ? kingMiddle | bit : kingMiddle & ~bit;
            else kingHigh = present ? kingHigh | bit : kingHigh & ~bit;
        }
    }

    private static int[][][] buildRays() {
        int[][][] rays = new int[BOARD_SIZE * BOARD_SIZE][DIRECTIONS.length][];
        for (int square = 0; square < rays.length; square++) {
            int row = BitBoard169.row(square);
            int col = BitBoard169.col(square);
            for (int d = 0; d < DIRECTIONS.length; d++) {
                int length = 0;
                int r = row + DIRECTIONS[d][0];
                int c = col + DIRECTIONS[d][1];
                while (r >= 0 && r < BOARD_SIZE && c >= 0 && c < BOARD_SIZE) {
                    length++;
                    r += DIRECTIONS[d][0];
                    c += DIRECTIONS[d][1];
                }
                int[] ray = new int[length];
                r = row + DIRECTIONS[d][0];
                c = col + DIRECTIONS[d][1];
                for (int i = 0; i < length; i++) {
                    ray[i] = BitBoard169.square(r, c);
                    r += DIRECTIONS[d][0];
                    c += DIRECTIONS[d][1];
                }
                rays[square][d] = ray;
            }
        }
        return rays;
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
