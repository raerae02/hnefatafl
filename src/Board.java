import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class Board {
    private static final int[][] DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private int kingRow, kingCol;
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
        makeMove(move, piece);
    }

    /*
     * Joue un coup directement sur ce plateau et retourne un masque indiquant
     * quelles directions ont produit une capture. La recherche peut ainsi
     * restaurer le plateau sans creer une copie complete pour chaque enfant.
     */
    private int makeMove(Move move, int piece) {
        movePiece(move, piece);
        return captureAround(move.toRow, move.toCol, piece);
    }

    private void unmakeMove(Move move, int piece, int captureMask) {
        grid[move.fromRow][move.fromCol] = piece;
        grid[move.toRow][move.toCol] = EMPTY;

        if (piece == KING) {
            kingRow = move.fromRow;
            kingCol = move.fromCol;
        }

        int capturedPiece = isAttacker(piece) ? BLACK : RED;
        for (int directionIndex = 0; directionIndex < DIRECTIONS.length; directionIndex++) {
            if ((captureMask & (1 << directionIndex)) == 0) continue;

            int[] direction = DIRECTIONS[directionIndex];
            int capturedRow = move.toRow + direction[0];
            int capturedCol = move.toCol + direction[1];
            grid[capturedRow][capturedCol] = capturedPiece;
        }
    }

    private void movePiece(Move move, int piece) {
        grid[move.toRow][move.toCol] = piece;
        grid[move.fromRow][move.fromCol] = EMPTY;

        if (piece == KING) {
            kingRow = move.toRow;
            kingCol = move.toCol;
        }
    }

    private int captureAround(int row, int col, int piece) {
        int captureMask = 0;

        for (int directionIndex = 0; directionIndex < DIRECTIONS.length; directionIndex++) {
            int[] direction = DIRECTIONS[directionIndex];
            int victimRow = row + direction[0];
            int victimCol = col + direction[1];
            int otherSideRow = row + 2 * direction[0];
            int otherSideCol = col + 2 * direction[1];

            if (!inBounds(victimRow, victimCol)) continue;

            int victim = grid[victimRow][victimCol];
            if (victim != EMPTY && !sameTeam(victim, piece) && victim != KING
                    && shouldCapture(otherSideRow, otherSideCol, piece)) {
                grid[victimRow][victimCol] = EMPTY;
                captureMask |= 1 << directionIndex;
            }
        }

        return captureMask;
    }

    private boolean shouldCapture(int row, int col, int piece) {
        if (!inBounds(row, col)) return false;

        // le trone est hostile meme quand le roi est dessus (comportement du serveur)
        if (isThrone(row, col)) return true;

        int otherSide = grid[row][col];
        return sameTeam(otherSide, piece) || (otherSide == EMPTY && isHostileSquare(row, col));
    }

    // tous les coups valides pour rouge ou noir
    public List<Move> getLegalMoves(int player) {
        List<Move> moves = new ArrayList<>();
        scanMoves(player, moves);
        return moves;
    }

    // nombre de coups valides, sans creer d'objets (pour la mobilite dans evaluate)
    private int countMoves(int player) {
        return scanMoves(player, null);
    }

    /*
     * Parcours commun : suit les 4 rayons de chaque piece du joueur (mouvement
     * de tour) et retourne le nombre de coups valides. Si collector n'est pas
     * null, chaque coup y est aussi ajoute. Le mode "comptage seul" evite de
     * creer des milliers d'objets Move dans evaluate, appelee a chaque feuille
     * de la recherche.
     */
    private int scanMoves(int player, List<Move> collector) {
        int count = 0;
        for (int fromRow = 0; fromRow < 13; fromRow++) {
            for (int fromCol = 0; fromCol < 13; fromCol++) {
                int piece = grid[fromRow][fromCol];
                boolean isMyPiece = (isAttacker(player)) ? (piece == RED) : (piece == BLACK || piece == KING);
                if(!isMyPiece) continue;
                for (int[] direction : DIRECTIONS) {
                    int toRow = fromRow + direction[0];
                    int toCol = fromCol + direction[1];
                    while (inBounds(toRow, toCol) && grid[toRow][toCol] == EMPTY) {
                        // seulement le roi peut s'arreter sur le trone ou dans un coin
                        if (piece == KING || !isHostileSquare(toRow, toCol)) {
                            count++;
                            if (collector != null) collector.add(new Move(fromRow, fromCol, toRow, toCol));
                        }
                        toRow += direction[0];
                        toCol += direction[1];
                    }
                }
            }
        }
        return count;
    }

    private static long searchDeadline = Long.MAX_VALUE;
    public static int lastSearchDepth = 0;

    /*
     * Approfondissement iteratif : cherche a profondeur 2, puis 3, puis 4...
     * tant qu'il reste du temps. On garde le coup de la derniere profondeur
     * COMPLETEE (une recherche interrompue est jetee). La profondeur 2 est
     * toujours terminee pour garantir un coup valable.
     *
     * A chaque iteration, les coups sont essayes dans l'ordre des scores de
     * l'iteration precedente : commencer par les meilleurs resserre la fenetre
     * alpha tres tot, ce qui fait couper l'alpha-beta beaucoup plus vite et
     * permet d'atteindre des profondeurs superieures dans le meme budget.
     */
    public Move getBestMoveTimed(int player, long timeBudgetMs, Map<String, Integer> positionHistory) {
        long deadline = System.currentTimeMillis() + timeBudgetMs;

        List<Move> moves = getLegalMoves(player);
        if (moves.isEmpty()) return null;

        Map<String, Integer> previousScores = new HashMap<>();
        Move bestMove = null;
        lastSearchDepth = 0;

        for (int depth = 2; depth <= 12; depth++) {
            searchDeadline = (depth == 2) ? Long.MAX_VALUE : deadline;
            try {
                if (!previousScores.isEmpty()) {
                    Map<String, Integer> order = previousScores;
                    moves.sort((a, b) -> Integer.compare(
                            order.getOrDefault(b.toString(), Integer.MIN_VALUE),
                            order.getOrDefault(a.toString(), Integer.MIN_VALUE)));
                }

                Move iterationBest = null;
                int iterationBestScore = Integer.MIN_VALUE;
                int alpha = Integer.MIN_VALUE;
                Map<String, Integer> scores = new HashMap<>();

                for (Move move : moves) {
                    int piece = grid[move.fromRow][move.fromCol];
                    int captureMask = makeMove(move, piece);
                    int score;
                    try {
                        score = alphaBeta(depth - 1, opponent(player), player,
                                alpha, Integer.MAX_VALUE);

                        if (positionHistory != null) {
                            Integer timesSeen = positionHistory.get(positionKey());
                            if (timesSeen != null) score -= timesSeen * 500;
                        }
                    } finally {
                        unmakeMove(move, piece, captureMask);
                    }

                    scores.put(move.toString(), score);
                    if (iterationBest == null || score > iterationBestScore) {
                        iterationBest = move;
                        iterationBestScore = score;
                    }
                    // borne prudente : le score penalise est toujours <= au score brut
                    alpha = Math.max(alpha, score);
                }

                bestMove = iterationBest;
                lastSearchDepth = depth;
                previousScores = scores;
            } catch (SearchTimeout e) {
                break;
            } finally {
                searchDeadline = Long.MAX_VALUE;
            }
        }

        return bestMove;
    }

    /*
     * Ordonne les coups pour l'alpha-beta : captures d'abord, puis coups du roi,
     * puis le reste. Essayer les coups forts en premier resserre alpha/beta tot
     * et fait couper la recherche beaucoup plus vite, donc on va plus profond.
     */
    private List<Move> orderMoves(List<Move> moves) {
        List<Move> ordered = new ArrayList<>(moves.size());
        List<Move> kingMoves = new ArrayList<>();
        List<Move> quiet = new ArrayList<>(moves.size());

        for (Move move : moves) {
            if (isCapturingMove(move)) ordered.add(move);
            else if (grid[move.fromRow][move.fromCol] == KING) kingMoves.add(move);
            else quiet.add(move);
        }

        ordered.addAll(kingMoves);
        ordered.addAll(quiet);
        return ordered;
    }

    // Estimation rapide (sans jouer le coup) : ce coup capture-t-il une piece ?
    private boolean isCapturingMove(Move move) {
        int piece = grid[move.fromRow][move.fromCol];
        for (int[] direction : DIRECTIONS) {
            int victimRow = move.toRow + direction[0];
            int victimCol = move.toCol + direction[1];
            if (!inBounds(victimRow, victimCol)) continue;

            int victim = grid[victimRow][victimCol];
            if (victim != EMPTY && victim != KING && !sameTeam(victim, piece)
                    && shouldCapture(move.toRow + 2 * direction[0], move.toCol + 2 * direction[1], piece)) {
                return true;
            }
        }
        return false;
    }

    // Signature compacte de la position, utilisee pour detecter les repetitions.
    public String positionKey() {
        StringBuilder key = new StringBuilder(169);
        for (int row = 0; row < 13; row++) {
            for (int col = 0; col < 13; col++) {
                key.append((char) ('0' + grid[row][col]));
            }
        }
        return key.toString();
    }

    /*
     * Minimax explore les coups possibles jusqu'a une certaine profondeur.
     * - player represente le joueur dont c'est le tour dans ce niveau de recherche.
     * - playerToHelp represente le joueur pour lequel on cherche le meilleur coup.
     *
     * La methode joue chaque coup directement, appelle recursivement minimax pour
     * le joueur adverse, puis restaure le plateau. Quand la profondeur arrive a 0,
     * ou quand la partie est terminee, evaluate(playerToHelp) donne une note au plateau.
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
            int piece = grid[move.fromRow][move.fromCol];
            int captureMask = makeMove(move, piece);
            int score;
            try {
                score = minimax(depth - 1, opponent(player), playerToHelp);
            } finally {
                unmakeMove(move, piece, captureMask);
            }

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
     * le coup est joue puis annule sur le meme plateau pendant qu'alphaBeta estime
     * la suite. Si beta <= alpha, la branche est arretee avec break, car elle ne
     * peut pas produire un meilleur choix que ce qui a deja ete trouve.
     */
    private int alphaBeta(int depth, int player, int playerToHelp, int alpha, int beta) {
        if (System.currentTimeMillis() > searchDeadline) throw new SearchTimeout();
        if (depth == 0 || isTerminal()) return evaluate(playerToHelp);

        List<Move> moves = orderMoves(getLegalMoves(player));
        if (moves.isEmpty()) return evaluate(playerToHelp);

        if (player == playerToHelp) {
            int bestScore = Integer.MIN_VALUE;
            for (Move move : moves) {
                int piece = grid[move.fromRow][move.fromCol];
                int captureMask = makeMove(move, piece);
                int score;
                try {
                    score = alphaBeta(depth - 1, opponent(player), playerToHelp, alpha, beta);
                } finally {
                    unmakeMove(move, piece, captureMask);
                }
                bestScore = Math.max(bestScore, score);
                alpha = Math.max(alpha, bestScore);
                if (beta <= alpha) break;
            }
            return bestScore;
        }

        int bestScore = Integer.MAX_VALUE;
        for (Move move : moves) {
            int piece = grid[move.fromRow][move.fromCol];
            int captureMask = makeMove(move, piece);
            int score;
            try {
                score = alphaBeta(depth - 1, opponent(player), playerToHelp, alpha, beta);
            } finally {
                unmakeMove(move, piece, captureMask);
            }
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
        int kingPressure = 0;

        for (int row = 0; row < 13; row++) {
            for (int col = 0; col < 13; col++) {
                if (grid[row][col] == RED) {
                    redPieces++;
                    // pression : plus un rouge est proche du roi, mieux c'est pour rouge
                    kingPressure += 24 - (Math.abs(row - kingRow) + Math.abs(col - kingCol));
                }
                if (grid[row][col] == BLACK) blackPieces++;
            }
        }

        int material = (blackPieces * 100) - (redPieces * 80);
        int mobility = (countMoves(BLACK) - countMoves(RED)) * 2;

        // poids asymetrique : le rouge valorise fortement fermer les cotes du roi,
        // le noir garde un roi audacieux qui fonce vers les coins
        int blockerWeight = isDefender(player) ? 30 : 60;
        int kingSafety = kingEscapeScore(blockerWeight);
        int defenderScore = material + mobility + kingSafety - (kingPressure * 2);

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

    // Verifie la validite d'un coup (exigence de l'enonce pour les coups adverses).
    boolean isValidMove(Move move){
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

    private int kingEscapeScore(int blockerWeight) {
        // roi colle a un coin libre : victoire noire imparable au prochain coup
        // (personne ne peut occuper le coin, s'interposer ou capturer le roi a temps)
        if (isCorner(kingRow - 1, kingCol) || isCorner(kingRow + 1, kingCol)
                || isCorner(kingRow, kingCol - 1) || isCorner(kingRow, kingCol + 1)) {
            return 50000;
        }

        int openCornerLines = 0;
        if (hasOpenCornerLine(0, -1)) openCornerLines++;
        if (hasOpenCornerLine(0, 1)) openCornerLines++;
        if (hasOpenCornerLine(-1, 0)) openCornerLines++;
        if (hasOpenCornerLine(1, 0)) openCornerLines++;

        // fourche : deux lignes ouvertes vers des coins, l'attaquant ne peut en bloquer qu'une
        if (openCornerLines >= 2) return 40000;

        int closestCorner = Math.min(
                Math.min(kingRow + kingCol, kingRow + (12 - kingCol)),
                Math.min((12 - kingRow) + kingCol, (12 - kingRow) + (12 - kingCol))
        );

        int score = (24 - closestCorner) * 10;
        score += openCornerLines * 250;

        int blockers = 0;
        if (isEvalBlocker(kingRow - 1, kingCol)) blockers++;
        if (isEvalBlocker(kingRow + 1, kingCol)) blockers++;
        if (isEvalBlocker(kingRow, kingCol - 1)) blockers++;
        if (isEvalBlocker(kingRow, kingCol + 1)) blockers++;

        // fermer un cote du roi vaut plus qu'un pion : c'est le chemin vers la capture
        return score - (blockers * blockerWeight);
    }

    // Pour l'evaluation : seuls un pion rouge ou le trone comptent comme bloqueurs.
    // Les bords restent capturants dans getWinner, mais les penaliser ici
    // decouragerait le roi d'utiliser les bords pour atteindre les coins.
    private boolean isEvalBlocker(int row, int col) {
        return inBounds(row, col) && (grid[row][col] == RED || isThrone(row, col));
    }

    // vrai si le roi a une ligne degagee jusqu'a un coin dans cette direction
    private boolean hasOpenCornerLine(int rowDirection, int colDirection) {
        int row = kingRow + rowDirection;
        int col = kingCol + colDirection;

        while (inBounds(row, col)) {
            if (isCorner(row, col)) return true;
            if (grid[row][col] != EMPTY) return false;
            row += rowDirection;
            col += colDirection;
        }

        return false;
    }
}
