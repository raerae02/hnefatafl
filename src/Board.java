import java.util.ArrayList;
import java.util.List;

class Board {
    private int kingRow, kingCol;
    private static final int DEFAULT_SEARCH_DEPTH = 2;
    private static final int WIN_SCORE = 100000;
    static final int EMPTY = 0, BLACK = 2, RED = 4, KING = 5;
    int[][] grid;

    public Board(int[][] grid){
        this.grid = grid;
        for (int row = 0; row < 13; row++) {
            for (int col = 0; col < 13; col++) {
                if(grid[row][col] == KING){
                    kingRow = row;
                    kingCol = col;
                }
            }
        }
    }

    // Deplace une piece et verifie les captures autour.
    public void applyMove(Move move) {
        int piece = grid[move.fromRow][move.fromCol];
        grid[move.toRow][move.toCol] = grid[move.fromRow][move.fromCol];
        grid[move.fromRow][move.fromCol] = EMPTY;

        if (piece == KING) {
            kingRow = move.toRow;
            kingCol = move.toCol;
        }

        if (inBounds(move.toRow-1, move.toCol)) {
            int victim = grid[move.toRow-1][move.toCol];

            if (victim != EMPTY && !sameTeam(victim, piece) && victim != KING) {
                boolean captured = false;
                if (inBounds(move.toRow-2, move.toCol)) {
                    int otherSide = grid[move.toRow-2][move.toCol];
                    captured = sameTeam(otherSide, piece) || (otherSide == EMPTY && isHostileSquare(move.toRow-2, move.toCol));
                }
                if (captured) grid[move.toRow-1][move.toCol] = EMPTY;
            }
        }
        if (inBounds(move.toRow+1, move.toCol)) {
            int victim = grid[move.toRow+1][move.toCol];

            if (victim != EMPTY && !sameTeam(victim, piece) && victim != KING) {
                boolean captured = false;
                if (inBounds(move.toRow+2, move.toCol)) {
                    int otherSide = grid[move.toRow+2][move.toCol];
                    captured = sameTeam(otherSide, piece) || (otherSide == EMPTY && isHostileSquare(move.toRow+2, move.toCol));
                }
                if (captured) grid[move.toRow+1][move.toCol] = EMPTY;
            }
        }

        if (inBounds(move.toRow, move.toCol-1)) {
            int victim = grid[move.toRow][move.toCol-1];

            if (victim != EMPTY && !sameTeam(victim, piece) && victim != KING) {
                boolean captured = false;
                if (inBounds(move.toRow, move.toCol-2)) {
                    int otherSide = grid[move.toRow][move.toCol-2];
                    captured = sameTeam(otherSide, piece) || (otherSide == EMPTY && isHostileSquare(move.toRow, move.toCol-2));
                }
                if (captured) grid[move.toRow][move.toCol-1] = EMPTY;
            }
        }

        if (inBounds(move.toRow, move.toCol+1)) {
            int victim = grid[move.toRow][move.toCol+1];

            if (victim != EMPTY && !sameTeam(victim, piece) && victim != KING) {
                boolean captured = false;
                if (inBounds(move.toRow, move.toCol+2)) {
                    int otherSide = grid[move.toRow][move.toCol+2];
                    captured = sameTeam(otherSide, piece) || (otherSide == EMPTY && isHostileSquare(move.toRow, move.toCol+2));
                }
                if (captured) grid[move.toRow][move.toCol+1] = EMPTY;
            }
        }
    }

    // tous les coups valides pour rouge ou noir
    public List<Move> getLegalMoves(int player) {
        List<Move> moves = new ArrayList<>();
        for (int fromRow = 0; fromRow < 13; fromRow++) {
            for (int fromCol = 0; fromCol < 13; fromCol++) {
                int piece = grid[fromRow][fromCol];
                boolean isMyPiece = (isAttacker(player)) ? (piece == RED) : (piece == BLACK || piece == KING);
                if(!isMyPiece) continue;
                for (int toCol = 0; toCol < 13; toCol++) {
                    Move move = new Move(fromRow, fromCol, fromRow, toCol);
                    if (isValidMove(move)) moves.add(move);
                }
                for (int toRow = 0; toRow < 13; toRow++) {
                    Move move = new Move(fromRow, fromCol, toRow, fromCol);
                    if (isValidMove(move)) moves.add(move);
                }
            }
        }
        return moves;
    }

    public int miniMax(Board board){
        return board.minimax(DEFAULT_SEARCH_DEPTH, RED, RED);
    }

    public Move getBestMove(int player, int depth) {
        List<Move> moves = getLegalMoves(player);
        if (moves.isEmpty()) return null;

        Move bestMove = null;
        int bestScore = Integer.MIN_VALUE;

        for (Move move : moves) {
            Board nextBoard = copy();
            nextBoard.applyMove(move);
            int score = nextBoard.alphaBeta(depth - 1, opponent(player), player,
                    Integer.MIN_VALUE, Integer.MAX_VALUE);

            if (bestMove == null || score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }

        return bestMove;
    }

    public int minimax(int depth, int player, int playerToHelp) {
        if (depth == 0 || isTerminal()) return evaluate(playerToHelp);

        List<Move> moves = getLegalMoves(player);
        if (moves.isEmpty()) return evaluate(playerToHelp);

        boolean isGoodPlayerTurn = player == playerToHelp;
        int bestScore = isGoodPlayerTurn ? Integer.MIN_VALUE : Integer.MAX_VALUE;

        for (Move move : moves) {
            Board nextBoard = copy();
            nextBoard.applyMove(move);
            int score = nextBoard.minimax(depth - 1, opponent(player), playerToHelp);

            if (isGoodPlayerTurn) {
                bestScore = Math.max(bestScore, score);
            } else {
                bestScore = Math.min(bestScore, score);
            }
        }

        return bestScore;
    }

    public int minimaxAlphaBeta(int depth, int player, int playerToHelp) {
        return alphaBeta(depth, player, playerToHelp, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private int alphaBeta(int depth, int player, int playerToHelp, int alpha, int beta) {
        if (depth == 0 || isTerminal()) return evaluate(playerToHelp);

        List<Move> moves = getLegalMoves(player);
        if (moves.isEmpty()) return evaluate(playerToHelp);

        if (player == playerToHelp) {
            int bestScore = Integer.MIN_VALUE;
            for (Move move : moves) {
                Board nextBoard = copy();
                nextBoard.applyMove(move);
                int score = nextBoard.alphaBeta(depth - 1, opponent(player), playerToHelp, alpha, beta);
                bestScore = Math.max(bestScore, score);
                alpha = Math.max(alpha, bestScore);
                if (beta <= alpha) break;
            }
            return bestScore;
        }

        int bestScore = Integer.MAX_VALUE;
        for (Move move : moves) {
            Board nextBoard = copy();
            nextBoard.applyMove(move);
            int score = nextBoard.alphaBeta(depth - 1, opponent(player), playerToHelp, alpha, beta);
            bestScore = Math.min(bestScore, score);
            beta = Math.min(beta, bestScore);
            if (beta <= alpha) break;
        }
        return bestScore;
    }

    public int evaluate(int player) {
        int winner = getWinner();
        if (winner != 0) {
            return sameTeam(winner, player) ? WIN_SCORE : -WIN_SCORE;
        }

        int redPieces = 0;
        int blackPieces = 0;

        for (int row = 0; row < 13; row++) {
            for (int col = 0; col < 13; col++) {
                if (grid[row][col] == RED) redPieces++;
                if (grid[row][col] == BLACK) blackPieces++;
            }
        }

        int material = (blackPieces * 100) - (redPieces * 80);
        int mobility = (getLegalMoves(BLACK).size() - getLegalMoves(RED).size()) * 2;
        int kingSafety = kingEscapeScore();
        int defenderScore = material + mobility + kingSafety;

        return isDefender(player) ? defenderScore : -defenderScore;
    }

    public Board copy() {
        int[][] copiedGrid = new int[13][13];
        for (int row = 0; row < 13; row++) {
            System.arraycopy(grid[row], 0, copiedGrid[row], 0, 13);
        }
        return new Board(copiedGrid);
    }

    public boolean isTerminal() { return getWinner() != 0; }

    public int getWinner() {
        // noir gagne si le roi arrive dans un coin
        if (isCorner(kingRow, kingCol)) return BLACK;

        // rouge gagne si le roi est bloque des 4 cotes
        if (isBlockedForKing(kingRow-1, kingCol) &&
                isBlockedForKing(kingRow+1, kingCol) &&
                isBlockedForKing(kingRow, kingCol-1) &&
                isBlockedForKing(kingRow, kingCol+1)) return RED;

        return 0;
    }

    public void print() {
        char[] symbols = {'.', '?', 'N', '?', 'R', 'K'};
        for (int row = 0; row < 13; row++) {
            System.out.printf("%2d  ", 13 - row);
            for (int col = 0; col < 13; col++) {
                System.out.print(symbols[grid[row][col]] + " ");
            }
            System.out.println();
        }
        System.out.println("    A B C D E F G H I J K L M");
    }

    private boolean isValidMove(Move move){
        int piece = grid[move.fromRow][move.fromCol];

        if(piece == EMPTY) return false;

        if(grid[move.toRow][move.toCol] != EMPTY) return false;

        boolean moveEndsOnThrone = isThrone(move.toRow, move.toCol);
        boolean moveEndsOnCorner = isCorner(move.toRow, move.toCol);

        // seulement le roi peut aller sur le trone ou dans un coin
        if ((moveEndsOnThrone || moveEndsOnCorner) && piece != KING) return false;

        // pas de mouvement en diagonale
        if (move.fromRow != move.toRow && move.fromCol != move.toCol) return false;

        // la piece doit vraiment bouger
        if(move.fromRow == move.toRow && move.fromCol == move.toCol) return false;

        return isPathClear(move);
    }

    private boolean isCorner(int row, int col) {
        return (row == 0 || row == 12) && (col == 0 || col == 12);
    }

    private boolean isThrone(int row, int col){
        return row == 6 && col == 6;
    }

    private boolean isPathClear(Move move){
        int rowDirection = Integer.signum(move.toRow - move.fromRow);
        int colDirection = Integer.signum(move.toCol - move.fromCol);

        int row = move.fromRow + rowDirection;
        int col = move.fromCol + colDirection;

        while (row != move.toRow || col != move.toCol){
            if(grid[row][col] != EMPTY) return false;
            row += rowDirection;
            col += colDirection;
        }

        return true;
    }

    private boolean isDefender(int piece) { return piece == BLACK || piece == KING; }

    private boolean isAttacker(int piece) { return piece == RED; }

    private boolean sameTeam(int firstPiece, int secondPiece) {
        return (isAttacker(firstPiece) && isAttacker(secondPiece)) || (isDefender(firstPiece) && isDefender(secondPiece));
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

    private int opponent(int player) {
        return isAttacker(player) ? BLACK : RED;
    }

    private int kingEscapeScore() {
        int closestCorner = Math.min(
                Math.min(kingRow + kingCol, kingRow + (12 - kingCol)),
                Math.min((12 - kingRow) + kingCol, (12 - kingRow) + (12 - kingCol))
        );

        int score = (24 - closestCorner) * 10;
        score += openKingLineScore(0, -1);
        score += openKingLineScore(0, 1);
        score += openKingLineScore(-1, 0);
        score += openKingLineScore(1, 0);

        int blockers = 0;
        if (isBlockedForKing(kingRow - 1, kingCol)) blockers++;
        if (isBlockedForKing(kingRow + 1, kingCol)) blockers++;
        if (isBlockedForKing(kingRow, kingCol - 1)) blockers++;
        if (isBlockedForKing(kingRow, kingCol + 1)) blockers++;

        return score - (blockers * 30);
    }

    private int openKingLineScore(int rowDirection, int colDirection) {
        int row = kingRow + rowDirection;
        int col = kingCol + colDirection;

        while (inBounds(row, col)) {
            if (grid[row][col] != EMPTY) return 0;
            if (isCorner(row, col)) return 250;
            row += rowDirection;
            col += colDirection;
        }

        return 0;
    }
}
