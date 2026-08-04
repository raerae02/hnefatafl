import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

class Board {
    private static final int[][] DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private int kingRow, kingCol;
    private static final int WIN_SCORE = 100000;
    static final int EMPTY = 0, BLACK = 2, RED = 4, KING = 5;
    int[][] grid;

    /*
     * Hachage de Zobrist : identifiant 64 bits de la position, mis a jour
     * incrementalement a chaque deplacement/capture (2-3 XOR) au lieu de
     * re-serialiser la grille. Sert de cle a la table de transposition.
     * Chaque combinaison (case, piece) recoit un nombre aleatoire fixe ;
     * le hash de la position est le XOR de ceux de ses pieces.
     */
    private static final long[][][] ZOBRIST = new long[13][13][6];
    private static final long ZOBRIST_RED_TURN;
    private static final long ZOBRIST_HELP_RED;
    static {
        Random random = new Random(987654321L);   // graine fixe : reproductible
        for (int row = 0; row < 13; row++) {
            for (int col = 0; col < 13; col++) {
                for (int piece = 0; piece < 6; piece++) {
                    ZOBRIST[row][col][piece] = random.nextLong();
                }
            }
        }
        ZOBRIST_RED_TURN = random.nextLong();
        ZOBRIST_HELP_RED = random.nextLong();
    }
    private long hash;

    public Board(int[][] grid){
        this.grid = grid;
        for (int row = 0; row < 13; row++) {
            for (int col = 0; col < 13; col++) {
                if(grid[row][col] == KING){
                    kingRow = row;
                    kingCol = col;
                }
                if (grid[row][col] != EMPTY) {
                    hash ^= ZOBRIST[row][col][grid[row][col]];
                }
            }
        }
        recomputeCounters();
    }

    // Copie directe : evite de rebalayer la grille pour retrouver roi et hash.
    private Board(int[][] grid, int kingRow, int kingCol, long hash) {
        this.grid = grid;
        this.kingRow = kingRow;
        this.kingCol = kingCol;
        this.hash = hash;
        recomputeCounters();
    }

    /*
     * Compteurs incrementaux : evaluate n'a plus besoin de balayer les 169
     * cases a chaque feuille. Ces valeurs sont mises a jour a chaque
     * deplacement/capture (movePiece, removePieceCounters) et restaurees
     * telles quelles par unmakeMove. sumDistRedToKing est la somme des
     * distances de Manhattan des pions rouges au roi ; la pression rouge
     * de l'evaluation s'en deduit : 24*redCount - sumDistRedToKing.
     */
    // cornerGuardNibbles : 4 compteurs de 4 bits (un par coin), nombre de
    // gardes rouges en place sur les cases de garde de ce coin (0 a 3)
    private int redCount, blackCount, sumDistRedToKing, cornerGuardNibbles;

    private void recomputeCounters() {
        redCount = 0;
        blackCount = 0;
        sumDistRedToKing = 0;
        cornerGuardNibbles = 0;
        for (int row = 0; row < 13; row++) {
            for (int col = 0; col < 13; col++) {
                if (grid[row][col] == RED) {
                    redCount++;
                    sumDistRedToKing += Math.abs(row - kingRow) + Math.abs(col - kingCol);
                    if (CORNER_INDEX[row][col] >= 0) cornerGuardNibbles += 1 << (4 * CORNER_INDEX[row][col]);
                } else if (grid[row][col] == BLACK) {
                    blackCount++;
                }
            }
        }
    }

    // Le roi a bouge : toutes les distances rouges changent, on rebalaye.
    // Cout paye seulement aux coups du roi, plus a chaque feuille.
    private void recomputeRedDistances() {
        sumDistRedToKing = 0;
        for (int row = 0; row < 13; row++) {
            for (int col = 0; col < 13; col++) {
                if (grid[row][col] == RED) {
                    sumDistRedToKing += Math.abs(row - kingRow) + Math.abs(col - kingCol);
                }
            }
        }
    }

    // Mise a jour des compteurs quand une piece (jamais le roi) est capturee.
    private void removePieceCounters(int victim, int row, int col) {
        if (victim == RED) {
            redCount--;
            sumDistRedToKing -= Math.abs(row - kingRow) + Math.abs(col - kingCol);
            if (CORNER_INDEX[row][col] >= 0) cornerGuardNibbles -= 1 << (4 * CORNER_INDEX[row][col]);
        } else {
            blackCount--;
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
        hash ^= ZOBRIST[move.fromRow][move.fromCol][piece] ^ ZOBRIST[move.toRow][move.toCol][piece];

        if (piece == KING) {
            kingRow = move.toRow;
            kingCol = move.toCol;
            recomputeRedDistances();
        } else if (piece == RED) {
            sumDistRedToKing += (Math.abs(move.toRow - kingRow) + Math.abs(move.toCol - kingCol))
                              - (Math.abs(move.fromRow - kingRow) + Math.abs(move.fromCol - kingCol));
            if (CORNER_INDEX[move.toRow][move.toCol] >= 0) cornerGuardNibbles += 1 << (4 * CORNER_INDEX[move.toRow][move.toCol]);
            if (CORNER_INDEX[move.fromRow][move.fromCol] >= 0) cornerGuardNibbles -= 1 << (4 * CORNER_INDEX[move.fromRow][move.fromCol]);
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
                hash ^= ZOBRIST[victimRow][victimCol][victim];
                removePieceCounters(victim, victimRow, victimCol);
            }
        }
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

    /*
     * Parcours commun : suit les 4 rayons de chaque piece du joueur (mouvement
     * de tour) et collecte les coups valides.
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

    // volatile : lus par les threads assistants, ecrits par le thread principal
    private static volatile long searchDeadline = Long.MAX_VALUE;
    public static int lastSearchDepth = 0;

    /*
     * Table de transposition (memoisation) : la meme position est souvent
     * atteinte par plusieurs ordres de coups differents. On memorise pour
     * chaque position exploree son score, la profondeur du calcul, le type
     * de borne (exact / minorant / majorant, a cause des coupures alpha-beta)
     * et le meilleur coup trouve. Tout est compacte dans un long :
     *   bits 0-31 : score   32-35 : profondeur   36-37 : type de borne
     *   bits 38-45 : case depart du meilleur coup   46-53 : case arrivee
     * Remplacement systematique (l'entree la plus recente gagne).
     */
    /*
     * Les scores de victoire dependent de la distance a la racine (WIN_SCORE - ply),
     * mais une meme position peut etre atteinte a des profondeurs differentes.
     * Dans la table, on stocke donc la distance DEPUIS LE NOEUD (independante de
     * la racine) : conversion a l'ecriture (toTT) et a la lecture (fromTT).
     */
    private static final int MATE_BOUND = 90000;

    private static int toTT(int score, int ply) {
        if (score > MATE_BOUND) return score + ply;
        if (score < -MATE_BOUND) return score - ply;
        return score;
    }

    private static int fromTT(int score, int ply) {
        if (score > MATE_BOUND) return score - ply;
        if (score < -MATE_BOUND) return score + ply;
        return score;
    }

    // 16 Mo : tient en grande partie dans le cache CPU (une table plus grande
    // s'est averee plus lente en pratique, defauts de cache a chaque acces)
    private static final int TT_SIZE = 1 << 20;               // ~1M entrees
    private static final long[] ttKeys = new long[TT_SIZE];
    private static final long[] ttData = new long[TT_SIZE];
    private static final int TT_EXACT = 0, TT_LOWER = 1, TT_UPPER = 2;

    // Mode agressif : reutilise aussi les scores calcules PLUS profond que demande
    // (plus profond = plus fiable). Active seulement pour la recherche chronometree ;
    // minimaxAlphaBeta reste strictement equivalent a minimax (test 9).
    private static volatile boolean ttAggressive = false;

    /*
     * Killer moves : a chaque niveau de profondeur, les 2 derniers coups ayant
     * provoque une coupure beta. Un coup qui refute un coup frere refute souvent
     * aussi les suivants -> l'essayer tot declenche la coupure immediatement.
     * History : score global par (case depart, case arrivee), incremente a chaque
     * coupure ; sert a trier les coups tranquilles.
     */
    private static final int MAX_DEPTH = 32;
    private static final int[] killer1 = new int[MAX_DEPTH];
    private static final int[] killer2 = new int[MAX_DEPTH];
    private static final int[][] historyTable = new int[169][169];

    private static int packMove(Move move) {
        return (move.fromRow * 13 + move.fromCol) * 169 + (move.toRow * 13 + move.toCol);
    }

    private static void recordCutoff(Move move, int depth) {
        int packed = packMove(move);
        if (killer1[depth] != packed) {
            killer2[depth] = killer1[depth];
            killer1[depth] = packed;
        }
        historyTable[move.fromRow * 13 + move.fromCol][move.toRow * 13 + move.toCol] += depth * depth;
    }

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
    // score de la derniere passe racine, pour la fenetre d'aspiration
    private int lastRootScore;
    private int previousBestScore;
    private boolean hasPreviousScore;

    public Move getBestMoveTimed(int player, long timeBudgetMs, Map<String, Integer> positionHistory) {
        long deadline = System.currentTimeMillis() + timeBudgetMs;
        hasPreviousScore = false;

        List<Move> moves = getLegalMoves(player);
        if (moves.isEmpty()) return null;

        // Vieillissement du history : on divise tout par 2 a chaque nouveau coup.
        // Les statistiques recentes dominent, les vieilles s'estompent, et les
        // compteurs ne peuvent pas deborder au fil d'une longue partie.
        for (int[] historyRow : historyTable) {
            for (int i = 0; i < historyRow.length; i++) historyRow[i] >>= 1;
        }

        Map<String, Integer> previousScores = new HashMap<>();
        Move bestMove = null;
        lastSearchDepth = 0;
        List<Thread> helpers = new ArrayList<>();

        try {
            for (int depth = 2; depth <= 12; depth++) {
                searchDeadline = (depth == 2) ? Long.MAX_VALUE : deadline;
                ttAggressive = true;

                // les assistants demarrent une fois la profondeur 2 garantie
                if (depth == 3) startHelpers(helpers, player);

                if (!previousScores.isEmpty()) {
                    Map<String, Integer> order = previousScores;
                    moves.sort((a, b) -> Integer.compare(
                            order.getOrDefault(b.toString(), Integer.MIN_VALUE),
                            order.getOrDefault(a.toString(), Integer.MIN_VALUE)));
                }

                /*
                 * Fenetre d'aspiration : le score d'une profondeur bouge
                 * rarement beaucoup par rapport a la precedente. On cherche
                 * d'abord dans une fenetre etroite autour du dernier score -
                 * les bornes serrees font couper enormement. Si le resultat
                 * sort de la fenetre (surprise), on refait la recherche avec
                 * la fenetre complete : le resultat reste donc toujours exact.
                 */
                Map<String, Integer> scores = new HashMap<>();
                Move iterationBest;
                if (depth >= 4 && hasPreviousScore) {
                    int windowAlpha = previousBestScore - 300;
                    int windowBeta = previousBestScore + 300;
                    iterationBest = searchRoot(moves, player, depth, positionHistory, scores,
                            windowAlpha, windowBeta);
                    if (lastRootScore <= windowAlpha || lastRootScore >= windowBeta) {
                        scores = new HashMap<>();
                        iterationBest = searchRoot(moves, player, depth, positionHistory, scores,
                                Integer.MIN_VALUE, Integer.MAX_VALUE);
                    }
                } else {
                    iterationBest = searchRoot(moves, player, depth, positionHistory, scores,
                            Integer.MIN_VALUE, Integer.MAX_VALUE);
                }
                previousBestScore = lastRootScore;
                hasPreviousScore = true;

                bestMove = iterationBest;
                lastSearchDepth = depth;
                previousScores = scores;
            }
        } catch (SearchTimeout e) {
            // temps ecoule : on garde le coup de la derniere profondeur completee
        } finally {
            searchDeadline = 0;                     // stoppe les assistants
            for (Thread helper : helpers) {
                try {
                    helper.join(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ttAggressive = false;
            searchDeadline = Long.MAX_VALUE;
        }

        return bestMove;
    }

    // Une passe de recherche sur les coups racine, avec fenetre alpha et
    // penalite d'anti-repetition. Remplit scoresOut pour le tri de l'iteration
    // suivante et retourne le meilleur coup de cette profondeur.
    private Move searchRoot(List<Move> moves, int player, int depth,
                            Map<String, Integer> positionHistory, Map<String, Integer> scoresOut,
                            int alphaInit, int betaInit) {
        Move best = null;
        int bestScore = Integer.MIN_VALUE;
        int alpha = alphaInit;

        for (Move move : moves) {
            makeMove(move);
            int score;
            String childKey;
            try {
                /*
                 * PVS a la racine : le premier coup (le mieux classe par
                 * l'iteration precedente) recoit la fenetre complete ; les
                 * suivants une fenetre nulle, juste pour prouver qu'ils sont
                 * moins bons. Si l'un d'eux surprend, re-recherche complete.
                 */
                if (best == null || !ttAggressive || alpha == Integer.MIN_VALUE) {
                    score = alphaBeta(depth - 1, opponent(player), player,
                            alpha, betaInit);
                } else {
                    score = alphaBeta(depth - 1, opponent(player), player, alpha, alpha + 1);
                    if (score > alpha) {
                        score = alphaBeta(depth - 1, opponent(player), player,
                                alpha, betaInit);
                    }
                }
                childKey = (positionHistory != null) ? positionKey() : null;
            } finally {
                unmakeMove(move);
            }

            if (positionHistory != null) {
                Integer timesSeen = positionHistory.get(childKey);
                if (timesSeen != null) score -= timesSeen * 500;
            }

            if (scoresOut != null) scoresOut.put(move.toString(), score);
            if (best == null || score > bestScore) {
                best = move;
                bestScore = score;
            }
            // borne prudente : le score penalise est toujours <= au score brut
            alpha = Math.max(alpha, score);
            // fenetre d'aspiration depassee : inutile de continuer, la passe
            // sera refaite avec la fenetre complete
            if (alpha >= betaInit) break;
        }
        lastRootScore = bestScore;
        return best;
    }

    /*
     * Lazy SMP : chaque assistant relance la meme recherche iterative sur sa
     * propre copie du plateau. Il ne communique que par la table de
     * transposition partagee : ses scores et meilleurs coups memorises font
     * couper le thread principal beaucoup plus tot. Les assistants s'arretent
     * quand searchDeadline passe a 0.
     */
    private static final int HELPER_THREADS =
            Math.max(0, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));

    /*
     * Pondering : pendant que l'ADVERSAIRE reflechit, on continue de chercher
     * sur la position courante et on remplit la table de transposition. Quand
     * notre tour arrive, la recherche demarre avec une table deja chaude -
     * les sous-arbres se recoupent quel que soit le coup adverse joue.
     */
    private static Thread ponderThread = null;

    public void startPondering(int playerToMove) {
        stopPondering();
        Board local = copy();
        ponderThread = new Thread(() -> {
            try {
                ttAggressive = true;
                searchDeadline = Long.MAX_VALUE;
                for (int depth = 2; depth <= 12; depth++) {
                    local.searchRoot(local.getLegalMoves(playerToMove), playerToMove, depth, null, null,
                            Integer.MIN_VALUE, Integer.MAX_VALUE);
                }
            } catch (SearchTimeout ignored) {
                // arret normal demande par stopPondering
            } finally {
                ttAggressive = false;
            }
        });
        ponderThread.setDaemon(true);
        ponderThread.start();
    }

    public static void stopPondering() {
        if (ponderThread == null) return;
        searchDeadline = 0;                 // reveille le thread via SearchTimeout
        try {
            ponderThread.join(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ponderThread = null;
        searchDeadline = Long.MAX_VALUE;
    }

    private void startHelpers(List<Thread> helpers, int player) {
        for (int i = 0; i < HELPER_THREADS; i++) {
            // profondeurs de depart alternees pour diversifier les decouvertes
            final int startDepth = 3 + (i % 2);
            Board localBoard = copy();
            Thread helper = new Thread(() -> {
                try {
                    for (int depth = startDepth; depth <= 12; depth++) {
                        localBoard.searchRoot(localBoard.getLegalMoves(player), player, depth, null, null,
                                Integer.MIN_VALUE, Integer.MAX_VALUE);
                    }
                } catch (SearchTimeout ignored) {
                    // fin normale : le temps est ecoule
                }
            });
            helper.setDaemon(true);
            helper.start();
            helpers.add(helper);
        }
    }

    /*
     * Ordonne les coups pour l'alpha-beta : meilleur coup memorise dans la
     * table de transposition, puis captures, puis killer moves du niveau,
     * puis coups du roi, puis le reste trie par history (les coups tranquilles
     * qui ont souvent provoque des coupures passent en premier). Essayer les
     * coups forts en premier resserre alpha/beta tot et fait couper la
     * recherche beaucoup plus vite, donc on va plus profond.
     */
    private static final int SCORE_CAPTURE = 1_500_000_000;
    private static final int SCORE_KILLER  = 1_400_000_000;
    private static final int SCORE_KING    = 1_300_000_000;
    private static final int SCORE_QUIET_CAP = 1_200_000_000;

    private void orderMoves(Move[] buffer, int count, int depth, int ttFrom, int ttTo) {
        for (int i = 0; i < count; i++) {
            Move move = buffer[i];
            int from = move.fromRow * 13 + move.fromCol;
            int to = move.toRow * 13 + move.toCol;
            if (from == ttFrom && to == ttTo) move.sortScore = Integer.MAX_VALUE;
            else if (isCapturingMove(move)) move.sortScore = SCORE_CAPTURE;
            else {
                int packed = from * 169 + to;
                if (packed == killer1[depth] || packed == killer2[depth]) move.sortScore = SCORE_KILLER;
                else if (grid[move.fromRow][move.fromCol] == KING) move.sortScore = SCORE_KING;
                else move.sortScore = Math.min(historyTable[from][to], SCORE_QUIET_CAP);
            }
        }
        // tri par insertion, stable et sans allocation (le tri de List creait
        // un tableau temporaire a CHAQUE noeud)
        for (int i = 1; i < count; i++) {
            Move move = buffer[i];
            int score = move.sortScore;
            int j = i - 1;
            while (j >= 0 && buffer[j].sortScore < score) {
                buffer[j + 1] = buffer[j];
                j--;
            }
            buffer[j + 1] = move;
        }
    }

    /*
     * Generation sans allocation : les objets Move de chaque niveau (ply)
     * sont mis en commun et reutilises d'une recherche a l'autre - on ne fait
     * que reecrire leurs champs. Avant : une ArrayList + des dizaines de Move
     * neufs a CHAQUE noeud, des millions par coup.
     */
    private final Move[][] movePool = new Move[MAX_PLY][];

    private int fillMoves(int player) {
        Move[] pool = movePool[ply];
        if (pool == null) movePool[ply] = pool = new Move[192];
        int count = 0;
        for (int fromRow = 0; fromRow < 13; fromRow++) {
            for (int fromCol = 0; fromCol < 13; fromCol++) {
                int piece = grid[fromRow][fromCol];
                boolean isMyPiece = (isAttacker(player)) ? (piece == RED) : (piece == BLACK || piece == KING);
                if (!isMyPiece) continue;
                for (int[] direction : DIRECTIONS) {
                    int toRow = fromRow + direction[0];
                    int toCol = fromCol + direction[1];
                    while (inBounds(toRow, toCol) && grid[toRow][toCol] == EMPTY) {
                        if (piece == KING || !isHostileSquare(toRow, toCol)) {
                            if (count == pool.length) {
                                movePool[ply] = pool = java.util.Arrays.copyOf(pool, pool.length * 2);
                            }
                            Move move = pool[count];
                            if (move == null) {
                                pool[count] = new Move(fromRow, fromCol, toRow, toCol);
                            } else {
                                move.fromRow = fromRow;
                                move.fromCol = fromCol;
                                move.toRow = toRow;
                                move.toCol = toCol;
                            }
                            count++;
                        }
                        toRow += direction[0];
                        toCol += direction[1];
                    }
                }
            }
        }
        return count;
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
        if (System.currentTimeMillis() > searchDeadline) throw new SearchTimeout();
        // Victoire ajustee par la distance : une victoire proche (peu de demi-coups
        // depuis la racine) vaut PLUS qu'une victoire lointaine. Sans cela, gagner
        // en 2 coups ou en 8 donne le meme score et l'IA peut tourner en rond au
        // bord d'une position gagnee sans jamais conclure. Symetriquement, une
        // defaite lointaine vaut mieux qu'une defaite immediate (on se debat).
        if (isTerminal()) {
            int winner = getWinner();
            return sameTeam(winner, playerToHelp) ? WIN_SCORE - ply : -(WIN_SCORE - ply);
        }
        // Quiescence desactivee pour l'instant : son cout par feuille (une
        // generation de coups complete) faisait perdre un niveau de profondeur,
        // mesure a l'arene. A reactiver avec un generateur de captures dedie.
        if (depth <= 0) return evaluate(playerToHelp);

        // --- consultation de la table de transposition ---
        // La cle combine la position (hash), le trait et le camp qu'on aide,
        // car le score d'une position depend des trois.
        long key = hash;
        if (player == RED) key ^= ZOBRIST_RED_TURN;
        if (playerToHelp == RED) key ^= ZOBRIST_HELP_RED;
        int index = (int) (key & (TT_SIZE - 1));

        // astuce XOR anti "entree dechiree" : la cle est stockee XOR les donnees ;
        // si un autre thread ecrit en meme temps, la verification echoue simplement
        int ttFrom = -1, ttTo = -1;
        long data = ttData[index];
        if ((ttKeys[index] ^ data) == key) {
            int storedScore = fromTT((int) data, ply);
            int storedDepth = (int) ((data >>> 32) & 0xF);
            int storedFlag = (int) ((data >>> 36) & 0x3);

            if (storedDepth == depth || (ttAggressive && storedDepth > depth)) {
                if (storedFlag == TT_EXACT) return storedScore;
                if (storedFlag == TT_LOWER && storedScore > alpha) alpha = storedScore;
                if (storedFlag == TT_UPPER && storedScore < beta) beta = storedScore;
                if (alpha >= beta) return storedScore;
            }
            ttFrom = (int) ((data >>> 38) & 0xFF);
            ttTo = (int) ((data >>> 46) & 0xFF);
        }

        // Fenetre de reference pour etiqueter le resultat : capturee APRES le
        // resserrement par la table, sinon un score obtenu avec la fenetre
        // resserree pourrait etre etiquete "exact" a tort et empoisonner la table.
        int alphaOriginal = alpha;
        int betaOriginal = beta;

        int moveCount = fillMoves(player);
        if (moveCount == 0) return evaluate(playerToHelp);
        Move[] moves = movePool[ply];   // relu apres fillMoves (le pool peut grandir)
        orderMoves(moves, moveCount, depth, ttFrom, ttTo);

        /*
         * PVS (Principal Variation Search) + LMR (Late Move Reductions), actifs
         * seulement en recherche chronometree (ttAggressive) :
         * - le 1er coup (le mieux classe) est cherche avec la fenetre complete ;
         * - les suivants avec une fenetre nulle, juste pour prouver qu'ils sont
         *   pires - beaucoup plus rapide ;
         * - les coups tardifs (mal classes) sont en plus cherches moins profond ;
         * - si un coup surprend (bat la fenetre nulle), re-recherche complete.
         */
        Move bestMove = null;
        int bestScore;
        if (player == playerToHelp) {
            bestScore = Integer.MIN_VALUE;
            for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
                Move move = moves[moveIndex];
                makeMove(move);
                int score;
                try {
                    if (!ttAggressive || moveIndex == 0) {
                        score = alphaBeta(depth - 1, opponent(player), playerToHelp, alpha, beta);
                    } else {
                        int reduction = 0;
                        if (moveIndex >= 6 && depth >= 4) reduction = 1;
                        if (moveIndex >= 14 && depth >= 6) reduction = 2;
                        score = alphaBeta(depth - 1 - reduction, opponent(player), playerToHelp, alpha, alpha + 1);
                        if (score > alpha) {
                            score = alphaBeta(depth - 1, opponent(player), playerToHelp, alpha, beta);
                        }
                    }
                } finally {
                    unmakeMove(move);
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestMove = move;
                }
                alpha = Math.max(alpha, bestScore);
                if (beta <= alpha) {
                    recordCutoff(move, depth);
                    break;
                }
            }
        } else {
            bestScore = Integer.MAX_VALUE;
            for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
                Move move = moves[moveIndex];
                makeMove(move);
                int score;
                try {
                    if (!ttAggressive || moveIndex == 0) {
                        score = alphaBeta(depth - 1, opponent(player), playerToHelp, alpha, beta);
                    } else {
                        int reduction = 0;
                        if (moveIndex >= 6 && depth >= 4) reduction = 1;
                        if (moveIndex >= 14 && depth >= 6) reduction = 2;
                        score = alphaBeta(depth - 1 - reduction, opponent(player), playerToHelp, beta - 1, beta);
                        if (score < beta) {
                            score = alphaBeta(depth - 1, opponent(player), playerToHelp, alpha, beta);
                        }
                    }
                } finally {
                    unmakeMove(move);
                }
                if (score < bestScore) {
                    bestScore = score;
                    bestMove = move;
                }
                beta = Math.min(beta, bestScore);
                if (beta <= alpha) {
                    recordCutoff(move, depth);
                    break;
                }
            }
        }

        // --- memorisation du resultat ---
        // Une coupure signifie que le score n'est qu'une borne, pas une valeur
        // exacte : on memorise son type pour ne pas propager de valeurs fausses.
        int flag = (bestScore <= alphaOriginal) ? TT_UPPER
                 : (bestScore >= betaOriginal) ? TT_LOWER
                 : TT_EXACT;
        long newData = ((long) toTT(bestScore, ply)) & 0xFFFFFFFFL;
        newData |= ((long) Math.min(depth, 15)) << 32;
        newData |= ((long) flag) << 36;
        if (bestMove != null) {
            newData |= ((long) (bestMove.fromRow * 13 + bestMove.fromCol)) << 38;
            newData |= ((long) (bestMove.toRow * 13 + bestMove.toCol)) << 46;
        }
        ttKeys[index] = key ^ newData;
        ttData[index] = newData;

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
    /*
     * Blocus des coins : la strategie gagnante classique de l'attaquant dans
     * les tafl a sortie par les coins. Trois pions rouges sur la diagonale
     * d'un coin (ex. C13, B12, A11) scellent ce coin definitivement : le roi
     * ne peut plus y entrer. Quatre coins scelles = le roi ne peut plus sortir
     * du tout, et rouge peut resserrer l'etau tranquillement. On recompense
     * chaque case de garde occupee par un rouge.
     */
    /*
     * CORNER_INDEX[r][c] = numero du coin (0-3) que cette case de garde
     * protege, ou -1. Trois pions rouges sur la diagonale d'un coin
     * (ex. C13, B12, A11) scellent ce coin definitivement : le roi ne peut
     * plus y entrer. Quatre coins scelles = le roi ne peut plus sortir.
     */
    private static final int[][] CORNER_INDEX = new int[13][13];
    static {
        for (int[] row : CORNER_INDEX) java.util.Arrays.fill(row, -1);
        int[][] corners = {{0, 0}, {0, 12}, {12, 0}, {12, 12}};
        for (int c = 0; c < 4; c++) {
            int rowDir = (corners[c][0] == 0) ? 1 : -1;
            int colDir = (corners[c][1] == 0) ? 1 : -1;
            CORNER_INDEX[corners[c][0]][corners[c][1] + 2 * colDir] = c;      // ex. C13
            CORNER_INDEX[corners[c][0] + rowDir][corners[c][1] + colDir] = c; // ex. B12
            CORNER_INDEX[corners[c][0] + 2 * rowDir][corners[c][1]] = c;      // ex. A11
        }
    }

    public int evaluate(int player) {
        int winner = getWinner();
        if (winner != 0) {
            return sameTeam(winner, player) ? WIN_SCORE : -WIN_SCORE;
        }

        // Plus aucun balayage de la grille ici : tout vient des compteurs
        // incrementaux mis a jour par movePiece/removePieceCounters.
        int redPieces = redCount;
        int blackPieces = blackCount;
        // pression : plus un rouge est proche du roi, mieux c'est pour rouge
        int kingPressure = 24 * redCount - sumDistRedToKing;

        // 25 points par garde de coin en place (bareme plat, valide par arene)
        int guardScore = 0;
        for (int corner = 0; corner < 4; corner++) {
            guardScore += 25 * ((cornerGuardNibbles >>> (4 * corner)) & 0xF);
        }

        int material = (blackPieces * 100) - (redPieces * 80);

        // poids asymetrique : le rouge valorise fortement fermer les cotes du roi,
        // le noir garde un roi audacieux qui fonce vers les coins
        int blockerWeight = isDefender(player) ? 30 : 60;
        int kingSafety = kingEscapeScore(blockerWeight, isDefender(player));

        int defenderScore = material + kingSafety - (kingPressure * 2) - guardScore;

        return isDefender(player) ? defenderScore : -defenderScore;
    }

    /*
     * Jouer/annuler (make/unmake) : au lieu de copier les 169 cases du plateau
     * a chaque noeud de la recherche (des millions de fois par coup), on joue
     * le coup sur CE plateau, on explore, puis on le defait en restaurant
     * l'etat sauvegarde (victimes, roi, hash). Gain : plus aucune allocation
     * dans l'arbre.
     */
    private static final int MAX_PLY = 40;
    private final int[][] undoVictimSquare = new int[MAX_PLY][4];
    private final int[][] undoVictimPiece = new int[MAX_PLY][4];
    private final int[] undoVictimCount = new int[MAX_PLY];
    private final long[] undoHash = new long[MAX_PLY];
    private final int[] undoKing = new int[MAX_PLY];
    // compteurs incrementaux sauvegardes tels quels : l'annulation est une
    // simple restauration, aucune arithmetique inverse a maintenir
    private final int[] undoRedCount = new int[MAX_PLY];
    private final int[] undoBlackCount = new int[MAX_PLY];
    private final int[] undoSumDist = new int[MAX_PLY];
    private final int[] undoGuards = new int[MAX_PLY];
    private int ply = 0;

    private void makeMove(Move move) {
        undoHash[ply] = hash;
        undoKing[ply] = kingRow * 13 + kingCol;
        undoVictimCount[ply] = 0;
        undoRedCount[ply] = redCount;
        undoBlackCount[ply] = blackCount;
        undoSumDist[ply] = sumDistRedToKing;
        undoGuards[ply] = cornerGuardNibbles;

        int piece = grid[move.fromRow][move.fromCol];
        movePiece(move, piece);

        for (int[] direction : DIRECTIONS) {
            int victimRow = move.toRow + direction[0];
            int victimCol = move.toCol + direction[1];
            if (!inBounds(victimRow, victimCol)) continue;

            int victim = grid[victimRow][victimCol];
            if (victim != EMPTY && !sameTeam(victim, piece) && victim != KING
                    && shouldCapture(move.toRow + 2 * direction[0], move.toCol + 2 * direction[1], piece)) {
                int count = undoVictimCount[ply];
                undoVictimSquare[ply][count] = victimRow * 13 + victimCol;
                undoVictimPiece[ply][count] = victim;
                undoVictimCount[ply] = count + 1;
                grid[victimRow][victimCol] = EMPTY;
                hash ^= ZOBRIST[victimRow][victimCol][victim];
                removePieceCounters(victim, victimRow, victimCol);
            }
        }
        ply++;
    }

    private void unmakeMove(Move move) {
        ply--;
        for (int i = 0; i < undoVictimCount[ply]; i++) {
            int square = undoVictimSquare[ply][i];
            grid[square / 13][square % 13] = undoVictimPiece[ply][i];
        }
        grid[move.fromRow][move.fromCol] = grid[move.toRow][move.toCol];
        grid[move.toRow][move.toCol] = EMPTY;
        kingRow = undoKing[ply] / 13;
        kingCol = undoKing[ply] % 13;
        hash = undoHash[ply];   // restaure tous les XOR d'un coup
        redCount = undoRedCount[ply];
        blackCount = undoBlackCount[ply];
        sumDistRedToKing = undoSumDist[ply];
        cornerGuardNibbles = undoGuards[ply];
    }

    public Board copy() {
        int[][] copiedGrid = new int[13][13];
        for (int row = 0; row < 13; row++) {
            System.arraycopy(grid[row], 0, copiedGrid[row], 0, 13);
        }
        return new Board(copiedGrid, kingRow, kingCol, hash);
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

    private int kingEscapeScore(int blockerWeight, boolean helpingDefender) {
        // roi colle a un coin libre : victoire noire imparable au prochain coup
        // (personne ne peut occuper le coin, s'interposer ou capturer le roi a temps)
        if (isNextToCorner(kingRow, kingCol)) return 50000;

        int openCornerLines = 0;
        if (hasOpenCornerLine(0, -1)) openCornerLines++;
        if (hasOpenCornerLine(0, 1)) openCornerLines++;
        if (hasOpenCornerLine(-1, 0)) openCornerLines++;
        if (hasOpenCornerLine(1, 0)) openCornerLines++;

        // fourche : deux lignes ouvertes vers des coins, l'attaquant ne peut en bloquer qu'une
        if (openCornerLines >= 2) return 40000;

        /*
         * Menaces combinees a 1 et 2 coups : trois lanes a couvrir, l'attaquant
         * n'a pas assez de tempi pour toutes les fermer. ASYMETRIQUE (valide
         * par l'arene) : le palier n'existe que quand on evalue pour ROUGE -
         * prophylaxie, fuir ces positions tot. Symetrique, noir thesauriserait
         * le bonus (garder des menaces vaudrait plus que les executer).
         */
        int twoMovePaths = countTwoMoveCornerPaths();
        if (!helpingDefender && openCornerLines + twoMovePaths >= 3) return 20000;

        int closestCorner = Math.min(
                Math.min(kingRow + kingCol, kingRow + (12 - kingCol)),
                Math.min((12 - kingRow) + kingCol, (12 - kingRow) + (12 - kingCol))
        );

        int score = (24 - closestCorner) * 10;

        score += openCornerLines * 250;
        // gradient asymetrique : rouge craint fortement chaque couloir a 2 coups
        // (le fermer AVANT qu'il devienne une ligne directe) ; noir ne recoit
        // qu'un leger bonus - sa consigne reste d'EXECUTER l'evasion
        score += twoMovePaths * (helpingDefender ? 100 : 400);

        /*
         * Fermer un cote du roi vaut plus qu'un pion : c'est le chemin vers la
         * capture. MAIS un bloqueur dont la case situee juste derriere lui (vue
         * du roi) est libre est FRAGILE : l'adversaire s'y pose et le capture
         * en sandwich avec le roi lui-meme comme enclume (le roi participe aux
         * captures). Un bloqueur fragile ne vaut qu'un tiers - sinon l'IA
         * donne piece apres piece au meme moulin.
         */
        int blockerScore = 0;
        for (int[] direction : DIRECTIONS) {
            int row = kingRow + direction[0];
            int col = kingCol + direction[1];
            if (!isEvalBlocker(row, col)) continue;

            int beyondRow = kingRow + 2 * direction[0];
            int beyondCol = kingCol + 2 * direction[1];
            boolean fragile = grid[row][col] == RED
                    && inBounds(beyondRow, beyondCol)
                    && grid[beyondRow][beyondCol] != RED
                    && !isThrone(beyondRow, beyondCol);

            blockerScore += fragile ? blockerWeight / 3 : blockerWeight;
        }

        return score - blockerScore;
    }

    // Pour l'evaluation : seuls un pion rouge ou le trone comptent comme bloqueurs.
    // Les bords restent capturants dans getWinner, mais les penaliser ici
    // decouragerait le roi d'utiliser les bords pour atteindre les coins.
    private boolean isEvalBlocker(int row, int col) {
        return inBounds(row, col) && (grid[row][col] == RED || isThrone(row, col));
    }

    /*
     * Vrai si le roi a une ligne degagee, dans cette direction, jusqu'a un coin
     * OU jusqu'a une case vide voisine d'un coin. Le second cas est aussi mortel
     * que le premier : le roi s'y arrete et sort au coup suivant, imparable
     * (ex. colonne L ouverte -> L13 -> M13). Sans lui, les colonnes B/L et les
     * rangees 2/12 etaient des autoroutes invisibles vers les sorties.
     */
    private boolean hasOpenCornerLine(int rowDirection, int colDirection) {
        return hasOpenCornerLineFrom(kingRow, kingCol, rowDirection, colDirection);
    }

    private boolean hasOpenCornerLineFrom(int fromRow, int fromCol, int rowDirection, int colDirection) {
        int row = fromRow + rowDirection;
        int col = fromCol + colDirection;

        while (inBounds(row, col)) {
            if (isCorner(row, col)) return true;
            if (grid[row][col] != EMPTY) return false;
            if (isNextToCorner(row, col)) return true;
            row += rowDirection;
            col += colDirection;
        }

        return false;
    }

    /*
     * Menaces a DEUX coups ("tour du roi") : le roi glisse le long d'un rayon
     * jusqu'a une case d'arret S, d'ou une ligne PERPENDICULAIRE degagee mene
     * a un coin ou a une case vide voisine d'un coin. C'est exactement le
     * schema qui nous a battus deux fois (K11-K1 puis K3-M3/B3 puis le coin) :
     * invisible pour hasOpenCornerLine tant que le roi n'est pas DEJA sur S.
     * Au plus un chemin compte par rayon : boucher le rayon pres du roi coupe
     * d'un coup toutes les cases S de ce rayon.
     */
    private int countTwoMoveCornerPaths() {
        int paths = 0;
        for (int[] direction : DIRECTIONS) {
            int row = kingRow + direction[0];
            int col = kingCol + direction[1];
            boolean found = false;
            while (!found && inBounds(row, col) && grid[row][col] == EMPTY) {
                if (direction[0] == 0) {
                    found = hasOpenCornerLineFrom(row, col, -1, 0) || hasOpenCornerLineFrom(row, col, 1, 0);
                } else {
                    found = hasOpenCornerLineFrom(row, col, 0, -1) || hasOpenCornerLineFrom(row, col, 0, 1);
                }
                row += direction[0];
                col += direction[1];
            }
            if (found) paths++;
        }
        return paths;
    }

    private boolean isNextToCorner(int row, int col) {
        return isCorner(row - 1, col) || isCorner(row + 1, col)
                || isCorner(row, col - 1) || isCorner(row, col + 1);
    }
}