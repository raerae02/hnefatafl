import java.util.ArrayList;
import java.util.List;

class Board {
    private static final int[][] DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
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

        movePiece(move, piece);
        captureAround(move.toRow, move.toCol, piece);
    }

    private void movePiece(Move move, int piece) {
        grid[move.toRow][move.toCol] = piece;
        grid[move.fromRow][move.fromCol] = EMPTY;

        if (piece == KING) {
            kingRow = move.toRow;
            kingCol = move.toCol;
        }
    }

    private void captureAround(int row, int col, int piece) {
        for (int[] direction : DIRECTIONS) {
            int victimRow = row + direction[0];
            int victimCol = col + direction[1];
            int otherSideRow = row + 2 * direction[0];
            int otherSideCol = col + 2 * direction[1];

            if (!inBounds(victimRow, victimCol)) continue;

            int victim = grid[victimRow][victimCol];
            if (victim != EMPTY && !sameTeam(victim, piece) && victim != KING
                    && shouldCapture(otherSideRow, otherSideCol, piece)) {
                grid[victimRow][victimCol] = EMPTY;
            }
        }
    }

    private boolean shouldCapture(int row, int col, int piece) {
        if (!inBounds(row, col)) return false;

        int otherSide = grid[row][col];
        return sameTeam(otherSide, piece) || (otherSide == EMPTY && isHostileSquare(row, col));
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

    /*
     * Minimax explore les coups possibles jusqu'a une certaine profondeur.
     * - player represente le joueur dont c'est le tour dans ce niveau de recherche.
     * - playerToHelp represente le joueur pour lequel on cherche le meilleur coup.
     *
     * La methode simule chaque coup sur une copie du plateau, puis appelle recursivement
     * minimax pour le joueur adverse. Quand la profondeur arrive a 0, ou quand la partie
     * est terminee, evaluate(playerToHelp) donne une note au plateau.
     *
     * Si c'est le tour de playerToHelp, on garde le score le plus grand, car ce joueur
     * cherche a ameliorer sa position. Sinon, on garde le score le plus petit, car on
     * suppose que l'adversaire joue aussi le meilleur coup possible contre lui.
     */
    public int minimax(int depth, int player, int playerToHelp) {
        if (depth == 0 || isTerminal()) return evaluate(playerToHelp);

        List<Move> moves = getLegalMoves(player);
        if (moves.isEmpty()) return evaluate(playerToHelp);

        boolean isGoodPlayerTurn = player == playerToHelp;
        int bestScore;
        if (isGoodPlayerTurn) {
            bestScore = Integer.MIN_VALUE;
        } else {
            bestScore = Integer.MAX_VALUE;
        }

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

    /*
     * Version publique de minimax avec elagage alpha-beta.
     * Elle lance alphaBeta avec les bornes les plus larges possibles.
     */
    public int minimaxAlphaBeta(int depth, int player, int playerToHelp) {
        return alphaBeta(depth, player, playerToHelp, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /*
     * Alpha-beta fait le meme travail que minimax, mais evite d'explorer des branches
     * qui ne peuvent plus changer le resultat final.
     *
     * alpha est le meilleur score deja trouve pour le joueur qui maximise.
     * beta est le meilleur score deja trouve pour le joueur qui minimise.
     *
     * Dans ce code, getBestMove utilise alphaBeta pour noter chaque coup possible :
     * le coup est applique sur une copie du plateau, puis alphaBeta estime la suite
     * de la partie. Si beta <= alpha, la branche est arretee avec break, car elle
     * ne peut pas produire un meilleur choix que ce qui a deja ete trouve.
     */
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

    /*
     * evaluate donne une note numerique au plateau pour le joueur donne.
     * Cette note est utilisee par minimax et alphaBeta quand la recherche s'arrete.
     *
     * Une victoire donne un tres grand score positif pour l'equipe du joueur, et un
     * tres grand score negatif si l'adversaire a gagne. Sinon, la position est estimee
     * avec trois criteres :
     * - material : avantage en nombre de pieces.
     * - mobility : difference entre les coups possibles des defenseurs et des attaquants.
     * - kingSafety : facilite pour le roi de se rapprocher d'une sortie.
     *
     * Le score est calcule du point de vue des defenseurs, puis inverse si le joueur
     * demande est l'attaquant rouge. Ainsi, un score positif est toujours bon pour
     * le joueur passe en parametre.
     */
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
