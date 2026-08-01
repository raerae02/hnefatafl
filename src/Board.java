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
    }

    // Copie directe : evite de rebalayer la grille pour retrouver roi et hash.
    private Board(int[][] grid, int kingRow, int kingCol, long hash) {
        this.grid = grid;
        this.kingRow = kingRow;
        this.kingCol = kingCol;
        this.hash = hash;
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
    private static final int TT_SIZE = 1 << 20;               // ~1M entrees (16 Mo)
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
    public Move getBestMoveTimed(int player, long timeBudgetMs, Map<String, Integer> positionHistory) {
        long deadline = System.currentTimeMillis() + timeBudgetMs;

        List<Move> moves = getLegalMoves(player);
        if (moves.isEmpty()) return null;

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

                Map<String, Integer> scores = new HashMap<>();
                Move iterationBest = searchRoot(moves, player, depth, positionHistory, scores);

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
                            Map<String, Integer> positionHistory, Map<String, Integer> scoresOut) {
        Move best = null;
        int bestScore = Integer.MIN_VALUE;
        int alpha = Integer.MIN_VALUE;

        for (Move move : moves) {
            makeMove(move);
            int score;
            String childKey;
            try {
                score = alphaBeta(depth - 1, opponent(player), player,
                        alpha, Integer.MAX_VALUE);
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
        }
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
                    local.searchRoot(local.getLegalMoves(playerToMove), playerToMove, depth, null, null);
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
                        localBoard.searchRoot(localBoard.getLegalMoves(player), player, depth, null, null);
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
     * Recherche de quiescence : a la profondeur 0, on ne s'arrete pas net -
     * on explore encore les CAPTURES (jusqu'a 4 demi-coups) pour ne jamais
     * evaluer une position au milieu d'un echange. "Stand pat" : le joueur
     * au trait peut toujours refuser de capturer et garder son evaluation.
     */
    private int quiescence(int player, int playerToHelp, int alpha, int beta, int captureDepth) {
        if (System.currentTimeMillis() > searchDeadline) throw new SearchTimeout();
        if (isTerminal()) return evaluate(playerToHelp);

        int standPat = evaluate(playerToHelp);
        if (captureDepth <= 0) return standPat;

        boolean maximizing = (player == playerToHelp);
        if (maximizing) {
            if (standPat >= beta) return standPat;
            alpha = Math.max(alpha, standPat);
        } else {
            if (standPat <= alpha) return standPat;
            beta = Math.min(beta, standPat);
        }

        for (Move move : getLegalMoves(player)) {
            if (!isCapturingMove(move)) continue;

            Board nextBoard = copy();
            nextBoard.applyMove(move);
            int score = nextBoard.quiescence(opponent(player), playerToHelp, alpha, beta, captureDepth - 1);

            if (maximizing) {
                alpha = Math.max(alpha, score);
            } else {
                beta = Math.min(beta, score);
            }
            if (alpha >= beta) break;
        }

        return maximizing ? alpha : beta;
    }

    /*
     * Ordonne les coups pour l'alpha-beta : captures, puis killer moves du
     * niveau, puis coups du roi, puis le reste trie par history. Essayer les
     * coups forts en premier resserre alpha/beta tot et fait couper la
     * recherche beaucoup plus vite, donc on va plus profond.
     */
    private List<Move> orderMoves(List<Move> moves, int depth) {
        List<Move> ordered = new ArrayList<>(moves.size());
        List<Move> killers = new ArrayList<>(2);
        List<Move> kingMoves = new ArrayList<>();
        List<Move> quiet = new ArrayList<>(moves.size());

        for (Move move : moves) {
            int packed = packMove(move);
            if (isCapturingMove(move)) ordered.add(move);
            else if (packed == killer1[depth] || packed == killer2[depth]) killers.add(move);
            else if (grid[move.fromRow][move.fromCol] == KING) kingMoves.add(move);
            else quiet.add(move);
        }

        ordered.addAll(killers);
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
        if (isTerminal()) return evaluate(playerToHelp);
        // Quiescence desactivee pour l'instant : son cout par feuille (une
        // generation de coups complete) faisait perdre un niveau de profondeur,
        // mesure a l'arene. A reactiver avec un generateur de captures dedie.
        if (depth == 0) return evaluate(playerToHelp);

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
            int storedScore = (int) data;
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

        List<Move> moves = orderMoves(getLegalMoves(player), depth);
        if (moves.isEmpty()) return evaluate(playerToHelp);

        // le meilleur coup memorise pour cette position est essaye en premier
        if (ttFrom >= 0) {
            for (int i = 1; i < moves.size(); i++) {
                Move candidate = moves.get(i);
                if (candidate.fromRow * 13 + candidate.fromCol == ttFrom
                        && candidate.toRow * 13 + candidate.toCol == ttTo) {
                    moves.add(0, moves.remove(i));
                    break;
                }
            }
        }

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
            int moveIndex = 0;
            for (Move move : moves) {
                makeMove(move);
                int score;
                try {
                    if (!ttAggressive || moveIndex == 0) {
                        score = alphaBeta(depth - 1, opponent(player), playerToHelp, alpha, beta);
                    } else {
                        int reduction = (moveIndex >= 6 && depth >= 4) ? 1 : 0;
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
                moveIndex++;
            }
        } else {
            bestScore = Integer.MAX_VALUE;
            int moveIndex = 0;
            for (Move move : moves) {
                makeMove(move);
                int score;
                try {
                    if (!ttAggressive || moveIndex == 0) {
                        score = alphaBeta(depth - 1, opponent(player), playerToHelp, alpha, beta);
                    } else {
                        int reduction = (moveIndex >= 6 && depth >= 4) ? 1 : 0;
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
                moveIndex++;
            }
        }

        // --- memorisation du resultat ---
        // Une coupure signifie que le score n'est qu'une borne, pas une valeur
        // exacte : on memorise son type pour ne pas propager de valeurs fausses.
        int flag = (bestScore <= alphaOriginal) ? TT_UPPER
                 : (bestScore >= betaOriginal) ? TT_LOWER
                 : TT_EXACT;
        long newData = ((long) bestScore) & 0xFFFFFFFFL;
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

        // terme de mobilite supprime : il coutait deux balayages complets du
        // plateau a CHAQUE feuille (la moitie du cout d'evaluation) pour une
        // influence de quelques points - un niveau de profondeur vaut bien plus
        int mobility = 0;

        // poids asymetrique : le rouge valorise fortement fermer les cotes du roi,
        // le noir garde un roi audacieux qui fonce vers les coins
        int blockerWeight = isDefender(player) ? 30 : 60;
        int kingSafety = kingEscapeScore(blockerWeight);
        int defenderScore = material + mobility + kingSafety - (kingPressure * 2);

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
    private int ply = 0;

    private void makeMove(Move move) {
        undoHash[ply] = hash;
        undoKing[ply] = kingRow * 13 + kingCol;
        undoVictimCount[ply] = 0;

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

    private int kingEscapeScore(int blockerWeight) {
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

        int closestCorner = Math.min(
                Math.min(kingRow + kingCol, kingRow + (12 - kingCol)),
                Math.min((12 - kingRow) + kingCol, (12 - kingRow) + (12 - kingCol))
        );

        int score = (24 - closestCorner) * 10;

        score += openCornerLines * 250;

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
        int row = kingRow + rowDirection;
        int col = kingCol + colDirection;

        while (inBounds(row, col)) {
            if (isCorner(row, col)) return true;
            if (grid[row][col] != EMPTY) return false;
            if (isNextToCorner(row, col)) return true;
            row += rowDirection;
            col += colDirection;
        }

        return false;
    }

    private boolean isNextToCorner(int row, int col) {
        return isCorner(row - 1, col) || isCorner(row + 1, col)
                || isCorner(row, col - 1) || isCorner(row, col + 1);
    }
}
