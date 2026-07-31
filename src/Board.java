import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

class Board {
    static final int BOARD_SIZE = 13;
    private static final int SQUARE_COUNT = BOARD_SIZE * BOARD_SIZE;
    static final int WIN_SCORE = 100000;
    private static final int MAX_QUIESCENCE_DEPTH = 2;
    private static final int NO_ESCAPE_ROUTE = 99;
    static final int EMPTY = 0, BLACK = 2, RED = 4, KING = 5;
    private static final int[][] DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final int[][] CORNERS = {{0, 0}, {0, 12}, {12, 0}, {12, 12}};
    private static final int[][][] CORNER_GATEWAYS = {
            {{0, 1}, {1, 0}},
            {{0, 11}, {1, 12}},
            {{12, 1}, {11, 0}},
            {{12, 11}, {11, 12}}
    };
    private static final long[][] ZOBRIST_PIECES = new long[SQUARE_COUNT][6];
    private static final long[] ZOBRIST_SIDE_TO_MOVE = new long[6];
    private static final long[] ZOBRIST_PERSPECTIVE = new long[6];

    static {
        SplittableRandom random = new SplittableRandom(0x484E454641544146L);
        for (int square = 0; square < SQUARE_COUNT; square++) {
            for (int piece = 0; piece < ZOBRIST_PIECES[square].length; piece++) {
                ZOBRIST_PIECES[square][piece] = random.nextLong();
            }
        }
        ZOBRIST_SIDE_TO_MOVE[BLACK] = random.nextLong();
        ZOBRIST_SIDE_TO_MOVE[RED] = random.nextLong();
        ZOBRIST_PERSPECTIVE[BLACK] = random.nextLong();
        ZOBRIST_PERSPECTIVE[RED] = random.nextLong();
    }

    private int kingRow, kingCol;
    int[][] grid;
    private long zobristPiecesHash;

    public Board(int[][] grid){
        this.grid = grid;
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                int piece = grid[row][col];
                if (piece != EMPTY) {
                    zobristPiecesHash ^= zobristPiece(row, col, piece);
                }
                if(piece == KING){
                    kingRow = row;
                    kingCol = col;
                }
            }
        }
    }

    private Board(int[][] grid, int kingRow, int kingCol, long zobristPiecesHash) {
        this.grid = grid;
        this.kingRow = kingRow;
        this.kingCol = kingCol;
        this.zobristPiecesHash = zobristPiecesHash;
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
        zobristPiecesHash ^= zobristPiece(move.fromRow, move.fromCol, piece);
        zobristPiecesHash ^= zobristPiece(move.toRow, move.toCol, piece);
        movePiece(move, piece);
        return captureAround(move.toRow, move.toCol, piece);
    }

    private void unmakeMove(Move move, int piece, int captureMask) {
        zobristPiecesHash ^= zobristPiece(move.toRow, move.toCol, piece);
        zobristPiecesHash ^= zobristPiece(move.fromRow, move.fromCol, piece);
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
            zobristPiecesHash ^= zobristPiece(capturedRow, capturedCol, capturedPiece);
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
                zobristPiecesHash ^= zobristPiece(victimRow, victimCol, victim);
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
        TranspositionTable table = new TranspositionTable();
        SearchContext context = SearchContext.timed(
                player, timeBudgetMs, table, null, false);
        return searchBestMove(player, positionHistory, context).bestMove;
    }

    /*
     * Version generale utilisee par le client et par le pondering.
     * sideToMove est le joueur qui doit jouer a la racine, tandis que
     * context.playerToHelp() reste toujours le camp de notre intelligence.
     */
    SearchResult searchBestMove(int sideToMove, Map<String, Integer> positionHistory,
                                SearchContext context) {
        List<Move> moves = orderMoves(getLegalMoves(sideToMove), null);
        if (moves.isEmpty()) {
            return new SearchResult(
                    null, evaluate(context, sideToMove), 0, context);
        }

        boolean maximizingRoot = sideToMove == context.playerToHelp();
        Map<Move, Integer> previousScores = new HashMap<>();
        Move bestMove = moves.get(0);
        int bestScore = evaluate(context, sideToMove);
        int completedDepth = 0;

        Map<Move, Float> learnedPolicy = context.evaluator().rootPolicyScores(
                this, sideToMove, moves);
        if (!learnedPolicy.isEmpty()) {
            moves.sort((first, second) -> Integer.compare(
                    learnedRootOrderingScore(second, learnedPolicy),
                    learnedRootOrderingScore(first, learnedPolicy)));
            bestMove = moves.get(0);
        }

        for (int depth = context.minDepth(); depth <= context.maxDepth(); depth++) {
            context.beginIteration(depth);
            try {
                orderRootMoves(moves, previousScores, sideToMove,
                        maximizingRoot, context);

                RootIterationResult iteration = context.usesParallelRoot() && moves.size() > 1
                        ? searchRootParallel(moves, depth, sideToMove, positionHistory,
                                maximizingRoot, context)
                        : searchRootSequential(moves, depth, sideToMove, positionHistory,
                                maximizingRoot, context);

                bestMove = iteration.bestMove;
                bestScore = iteration.bestScore;
                completedDepth = depth;
                previousScores = iteration.scores;

                // L'historique de repetitions est applique uniquement a la racine.
                // Une valeur qui en depend ne doit pas etre reutilisee comme score exact.
                if (positionHistory == null) {
                    long rootKey = zobristKey(sideToMove, context.playerToHelp());
                    context.table().store(rootKey, depth, bestScore,
                            TranspositionTable.Bound.EXACT, bestMove,
                            context.tableGeneration());
                }
            } catch (SearchTimeout e) {
                break;
            }
        }

        return new SearchResult(bestMove, bestScore, completedDepth, context,
                previousScores, maximizingRoot);
    }

    private RootIterationResult searchRootSequential(
            List<Move> moves, int depth, int sideToMove,
            Map<String, Integer> positionHistory, boolean maximizingRoot,
            SearchContext context) {
        Move iterationBest = null;
        int iterationBestScore = maximizingRoot ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        int alpha = Integer.MIN_VALUE;
        int beta = Integer.MAX_VALUE;
        Map<Move, Integer> scores = new HashMap<>();

        for (Move move : moves) {
            context.checkStopped();
            int score = evaluateRootMoveOnCurrentBoard(
                    move, depth, sideToMove, positionHistory,
                    maximizingRoot, alpha, beta, context);
            scores.put(move, score);

            if (isBetterScore(score, iterationBestScore, maximizingRoot)
                    || iterationBest == null) {
                iterationBest = move;
                iterationBestScore = score;
            }

            if (maximizingRoot) alpha = Math.max(alpha, score);
            else beta = Math.min(beta, score);
        }

        return new RootIterationResult(iterationBest, iterationBestScore, scores);
    }

    /*
     * Young Brothers Wait simplifie : le premier coup, normalement le meilleur
     * grace a l'iteration precedente ou a la table, est cherche seul. Sa valeur
     * resserre ensuite la fenetre utilisee par les autres taches paralleles.
     */
    private RootIterationResult searchRootParallel(
            List<Move> moves, int depth, int sideToMove,
            Map<String, Integer> positionHistory, boolean maximizingRoot,
            SearchContext context) {
        Move firstMove = moves.get(0);
        int firstScore = evaluateRootMoveOnCurrentBoard(
                firstMove, depth, sideToMove, positionHistory,
                maximizingRoot, Integer.MIN_VALUE, Integer.MAX_VALUE, context);

        AtomicInteger sharedBound = new AtomicInteger(firstScore);
        List<Future<RootMoveScore>> futures = new ArrayList<>(moves.size() - 1);

        for (int index = 1; index < moves.size(); index++) {
            Move move = moves.get(index);
            futures.add(context.rootExecutor().submit(() -> {
                context.checkStopped();

                Board child = copy();
                child.applyMove(move);
                int alpha = maximizingRoot ? sharedBound.get() : Integer.MIN_VALUE;
                int beta = maximizingRoot ? Integer.MAX_VALUE : sharedBound.get();
                int score = child.alphaBeta(depth - 1, opponent(sideToMove),
                        alpha, beta, context);
                score = applyRepetitionPenalty(
                        child, score, positionHistory, maximizingRoot);

                if (maximizingRoot) {
                    sharedBound.accumulateAndGet(score, Math::max);
                } else {
                    sharedBound.accumulateAndGet(score, Math::min);
                }
                return new RootMoveScore(move, score);
            }));
        }

        Move iterationBest = firstMove;
        int iterationBestScore = firstScore;
        Map<Move, Integer> scores = new HashMap<>();
        scores.put(firstMove, firstScore);

        try {
            for (Future<RootMoveScore> future : futures) {
                RootMoveScore result = future.get();
                scores.put(result.move, result.score);
                if (isBetterScore(result.score, iterationBestScore, maximizingRoot)) {
                    iterationBest = result.move;
                    iterationBestScore = result.score;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.requestStop();
            cancelFutures(futures);
            throw new SearchTimeout();
        } catch (ExecutionException e) {
            context.requestStop();
            cancelFutures(futures);
            Throwable cause = e.getCause();
            if (cause instanceof SearchTimeout) throw (SearchTimeout) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("Echec d'une tache de recherche.", cause);
        }

        return new RootIterationResult(iterationBest, iterationBestScore, scores);
    }

    private int evaluateRootMoveOnCurrentBoard(
            Move move, int depth, int sideToMove,
            Map<String, Integer> positionHistory, boolean maximizingRoot,
            int alpha, int beta, SearchContext context) {
        int piece = grid[move.fromRow][move.fromCol];
        int captureMask = makeMove(move, piece);
        try {
            int score = alphaBeta(depth - 1, opponent(sideToMove),
                    alpha, beta, context);
            return applyRepetitionPenalty(
                    this, score, positionHistory, maximizingRoot);
        } finally {
            unmakeMove(move, piece, captureMask);
        }
    }

    private int applyRepetitionPenalty(
            Board movedBoard, int score, Map<String, Integer> positionHistory,
            boolean maximizingRoot) {
        if (positionHistory == null) return score;

        Integer timesSeen = positionHistory.get(movedBoard.positionKey());
        if (timesSeen == null) return score;

        int penalty = timesSeen * 500;
        return maximizingRoot ? score - penalty : score + penalty;
    }

    private void orderRootMoves(List<Move> moves, Map<Move, Integer> previousScores,
                                int sideToMove, boolean maximizingRoot,
                                SearchContext context) {
        if (!previousScores.isEmpty()) {
            moves.sort((first, second) -> {
                int firstScore = previousScores.getOrDefault(first, 0);
                int secondScore = previousScores.getOrDefault(second, 0);
                return maximizingRoot
                        ? Integer.compare(secondScore, firstScore)
                        : Integer.compare(firstScore, secondScore);
            });
        }

        long rootKey = zobristKey(sideToMove, context.playerToHelp());
        TranspositionTable.Entry rootEntry = context.table().find(rootKey);
        if (rootEntry != null) {
            context.recordTableHit();
            moveToFront(moves, rootEntry.bestMove());
        }
    }

    private boolean isBetterScore(int score, int currentBest, boolean maximizing) {
        return maximizing ? score > currentBest : score < currentBest;
    }

    private int learnedRootOrderingScore(
            Move move, Map<Move, Float> learnedPolicy) {
        int policyBonus = Math.round(
                learnedPolicy.getOrDefault(move, 0.0f) * 5_000.0f);
        policyBonus = Math.max(-40_000, Math.min(40_000, policyBonus));
        return movePriority(move) + policyBonus;
    }

    private void cancelFutures(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }

    /*
     * Ordre tactique puis strategique inspire des travaux sur le Hnefatafl :
     * victoire immediate, parade d'une fuite, capture, mobilisation du roi,
     * controle des portes de coin et resserrement du cordon. Le meilleur coup
     * de la table de transposition reste prioritaire.
     */
    private List<Move> orderMoves(List<Move> moves, Move preferredMove) {
        moves.sort((first, second) ->
                Integer.compare(movePriority(second), movePriority(first)));
        moveToFront(moves, preferredMove);
        return moves;
    }

    private void moveToFront(List<Move> moves, Move preferredMove) {
        if (preferredMove == null) return;

        int index = moves.indexOf(preferredMove);
        if (index > 0) {
            Move matchingMove = moves.remove(index);
            moves.add(0, matchingMove);
        }
    }

    private static final class RootMoveScore {
        final Move move;
        final int score;

        RootMoveScore(Move move, int score) {
            this.move = move;
            this.score = score;
        }
    }

    private static final class RootIterationResult {
        final Move bestMove;
        final int bestScore;
        final Map<Move, Integer> scores;

        RootIterationResult(Move bestMove, int bestScore, Map<Move, Integer> scores) {
            this.bestMove = bestMove;
            this.bestScore = bestScore;
            this.scores = scores;
        }
    }

    // Estimation rapide (sans jouer le coup) : ce coup capture-t-il une piece ?
    private boolean isCapturingMove(Move move) {
        return captureCountForMove(move) > 0;
    }

    private int captureCountForMove(Move move) {
        int piece = grid[move.fromRow][move.fromCol];
        int captures = 0;
        for (int[] direction : DIRECTIONS) {
            int victimRow = move.toRow + direction[0];
            int victimCol = move.toCol + direction[1];
            if (!inBounds(victimRow, victimCol)) continue;

            int victim = grid[victimRow][victimCol];
            if (victim != EMPTY && victim != KING && !sameTeam(victim, piece)
                    && shouldCapture(move.toRow + 2 * direction[0], move.toCol + 2 * direction[1], piece)) {
                captures++;
            }
        }
        return captures;
    }

    private int movePriority(Move move) {
        int piece = grid[move.fromRow][move.fromCol];
        int priority = 0;

        if ((piece == KING && isCorner(move.toRow, move.toCol))
                || (piece == RED && wouldCaptureKing(move))) {
            return 1_000_000;
        }

        if (piece == RED && blocksCurrentEscapeLine(move)) {
            priority += 350_000;
        }

        int captures = captureCountForMove(move);
        if (captures > 0) {
            priority += 120_000 + captures * 20_000;
        }

        int destinationGateway = cornerGatewayValue(move.toRow, move.toCol);
        int originGateway = cornerGatewayValue(move.fromRow, move.fromCol);

        if (piece == KING) {
            int before = closestCornerManhattan(move.fromRow, move.fromCol);
            int after = closestCornerManhattan(move.toRow, move.toCol);
            priority += 55_000 + (before - after) * 2_500;
            priority += destinationGateway * 8_000;
            if (isEdge(move.toRow, move.toCol)) priority += 12_000;
        } else if (piece == BLACK) {
            // Foot-in-the-Door et garde mobile : occuper une porte avant le barrage.
            priority += destinationGateway * 12_000;
            priority -= originGateway * 5_000;
            int distance = manhattan(move.toRow, move.toCol, kingRow, kingCol);
            if (distance <= 3) priority += (4 - distance) * 1_500;
        } else {
            // Un attaquant prefere un anneau a 2-4 cases au simple contact glouton.
            priority += destinationGateway * 14_000;
            priority -= originGateway * 6_000;
            int distance = manhattan(move.toRow, move.toCol, kingRow, kingCol);
            priority += Math.max(0, 5 - Math.abs(distance - 3)) * 1_200;
            if (move.toRow == kingRow || move.toCol == kingCol) priority += 4_000;
            if (manhattan(move.toRow, move.toCol, kingRow, kingCol) == 1) {
                priority += 25_000;
            }
        }

        return priority;
    }

    private boolean wouldCaptureKing(Move move) {
        int movingPiece = grid[move.fromRow][move.fromCol];
        if (movingPiece != RED) return false;

        for (int[] direction : DIRECTIONS) {
            int row = kingRow + direction[0];
            int col = kingCol + direction[1];
            if (!inBounds(row, col) || isCorner(row, col) || isThrone(row, col)) {
                continue;
            }

            int occupant;
            if (row == move.toRow && col == move.toCol) occupant = movingPiece;
            else if (row == move.fromRow && col == move.fromCol) occupant = EMPTY;
            else occupant = grid[row][col];

            if (occupant != RED) return false;
        }
        return true;
    }

    private boolean blocksCurrentEscapeLine(Move move) {
        for (int[] corner : CORNERS) {
            if (!hasClearRookLine(kingRow, kingCol, corner[0], corner[1])) continue;
            if (liesStrictlyBetween(move.toRow, move.toCol,
                    kingRow, kingCol, corner[0], corner[1])) {
                return true;
            }
        }
        return false;
    }

    private boolean hasClearRookLine(int fromRow, int fromCol, int toRow, int toCol) {
        if (fromRow != toRow && fromCol != toCol) return false;

        int rowDirection = Integer.signum(toRow - fromRow);
        int colDirection = Integer.signum(toCol - fromCol);
        int row = fromRow + rowDirection;
        int col = fromCol + colDirection;
        while (row != toRow || col != toCol) {
            if (grid[row][col] != EMPTY) return false;
            row += rowDirection;
            col += colDirection;
        }
        return grid[toRow][toCol] == EMPTY || isCorner(toRow, toCol);
    }

    private boolean liesStrictlyBetween(int row, int col,
                                        int firstRow, int firstCol,
                                        int secondRow, int secondCol) {
        if (firstRow == secondRow && row == firstRow) {
            return col > Math.min(firstCol, secondCol)
                    && col < Math.max(firstCol, secondCol);
        }
        if (firstCol == secondCol && col == firstCol) {
            return row > Math.min(firstRow, secondRow)
                    && row < Math.max(firstRow, secondRow);
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
        if (isTerminal()) return evaluate(playerToHelp, player);
        if (depth == 0) {
            return quiescenceMinimax(
                    player, playerToHelp, MAX_QUIESCENCE_DEPTH);
        }

        List<Move> moves = getLegalMoves(player);
        if (moves.isEmpty()) return evaluate(playerToHelp, player);

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

    private int quiescenceMinimax(int player, int playerToHelp,
                                  int remainingDepth) {
        if (isTerminal() || remainingDepth == 0) {
            return evaluate(playerToHelp, player);
        }

        boolean maximizing = player == playerToHelp;
        int bestScore = evaluate(playerToHelp, player);

        for (Move move : getLegalMoves(player)) {
            if (!isTacticalMove(move)) continue;

            int piece = grid[move.fromRow][move.fromCol];
            int captureMask = makeMove(move, piece);
            int score;
            try {
                score = quiescenceMinimax(
                        opponent(player), playerToHelp, remainingDepth - 1);
            } finally {
                unmakeMove(move, piece, captureMask);
            }

            if (isBetterScore(score, bestScore, maximizing)) {
                bestScore = score;
            }
        }
        return bestScore;
    }

    /*
     * Version publique de minimax avec elagage alpha-beta.
     * Elle lance alphaBeta avec les bornes les plus larges possibles.
     */
    public int minimaxAlphaBeta(int depth, int player, int playerToHelp) {
        TranspositionTable table = new TranspositionTable(16);
        SearchContext context = SearchContext.pondering(
                playerToHelp, table, null, false);
        return alphaBeta(depth, player, Integer.MIN_VALUE, Integer.MAX_VALUE, context);
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
    private int alphaBeta(int depth, int player, int alpha, int beta,
                          SearchContext context) {
        context.recordNode();
        context.checkStopped();

        if (isTerminal()) {
            context.recordLeaf();
            return evaluate(context, player);
        }

        if (depth == 0) {
            return quiescence(player, alpha, beta, MAX_QUIESCENCE_DEPTH, context);
        }

        int originalAlpha = alpha;
        int originalBeta = beta;
        long key = zobristKey(player, context.playerToHelp());
        TranspositionTable.Entry cached = context.table().find(key);
        Move preferredMove = null;

        if (cached != null) {
            context.recordTableHit();
            preferredMove = cached.bestMove();

            if (cached.depth >= depth) {
                if (cached.bound == TranspositionTable.Bound.EXACT) {
                    context.recordTableCutoff();
                    return cached.score;
                }
                if (cached.bound == TranspositionTable.Bound.LOWER) {
                    alpha = Math.max(alpha, cached.score);
                } else {
                    beta = Math.min(beta, cached.score);
                }
                if (alpha >= beta) {
                    context.recordTableCutoff();
                    return cached.score;
                }
            }
        }

        List<Move> moves = orderMoves(getLegalMoves(player), preferredMove);
        if (moves.isEmpty()) {
            context.recordLeaf();
            int score = evaluate(context, player);
            context.table().store(key, depth, score, TranspositionTable.Bound.EXACT,
                    null, context.tableGeneration());
            return score;
        }

        boolean maximizing = player == context.playerToHelp();
        int bestScore = maximizing ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        Move bestMove = null;

        for (Move move : moves) {
            int piece = grid[move.fromRow][move.fromCol];
            int captureMask = makeMove(move, piece);
            int score;
            try {
                score = alphaBeta(depth - 1, opponent(player), alpha, beta, context);
            } finally {
                unmakeMove(move, piece, captureMask);
            }

            if (bestMove == null || isBetterScore(score, bestScore, maximizing)) {
                bestScore = score;
                bestMove = move;
            }

            if (maximizing) alpha = Math.max(alpha, bestScore);
            else beta = Math.min(beta, bestScore);

            if (beta <= alpha) {
                context.recordCutoff();
                break;
            }
        }

        TranspositionTable.Bound bound;
        if (bestScore <= originalAlpha) {
            bound = TranspositionTable.Bound.UPPER;
        } else if (bestScore >= originalBeta) {
            bound = TranspositionTable.Bound.LOWER;
        } else {
            bound = TranspositionTable.Bound.EXACT;
        }

        context.table().store(key, depth, bestScore, bound, bestMove,
                context.tableGeneration());
        return bestScore;
    }

    /*
     * Recherche de stabilisation limitee. Une feuille n'est pas evaluee juste
     * avant une capture, une fuite du roi ou un blocage critique : ces coups
     * tactiques sont prolonges sur deux demi-coups au maximum.
     */
    private int quiescence(int player, int alpha, int beta, int remainingDepth,
                           SearchContext context) {
        context.checkStopped();

        if (isTerminal()) {
            context.recordLeaf();
            return evaluate(context, player);
        }

        int standPat = evaluate(context, player);
        context.recordLeaf();
        if (remainingDepth == 0) return standPat;

        boolean maximizing = player == context.playerToHelp();
        int bestScore = standPat;

        if (maximizing) {
            if (bestScore >= beta) return bestScore;
            alpha = Math.max(alpha, bestScore);
        } else {
            if (bestScore <= alpha) return bestScore;
            beta = Math.min(beta, bestScore);
        }

        List<Move> tacticalMoves = new ArrayList<>();
        for (Move move : getLegalMoves(player)) {
            if (isTacticalMove(move)) tacticalMoves.add(move);
        }
        orderMoves(tacticalMoves, null);

        for (Move move : tacticalMoves) {
            context.recordNode();
            context.checkStopped();

            int piece = grid[move.fromRow][move.fromCol];
            int captureMask = makeMove(move, piece);
            int score;
            try {
                score = quiescence(opponent(player), alpha, beta,
                        remainingDepth - 1, context);
            } finally {
                unmakeMove(move, piece, captureMask);
            }

            if (isBetterScore(score, bestScore, maximizing)) {
                bestScore = score;
            }

            if (maximizing) alpha = Math.max(alpha, bestScore);
            else beta = Math.min(beta, bestScore);

            if (beta <= alpha) {
                context.recordCutoff();
                break;
            }
        }

        return bestScore;
    }

    private boolean isTacticalMove(Move move) {
        int piece = grid[move.fromRow][move.fromCol];

        if ((piece == KING && isCorner(move.toRow, move.toCol))
                || (piece == RED && wouldCaptureKing(move))
                || isCapturingMove(move)) {
            return true;
        }

        if (piece == KING) {
            return isEdge(move.toRow, move.toCol)
                    || cornerGatewayValue(move.toRow, move.toCol) > 0;
        }

        if (piece == RED) {
            return blocksCurrentEscapeLine(move)
                    || manhattan(move.toRow, move.toCol, kingRow, kingCol) == 1;
        }

        return manhattan(move.fromRow, move.fromCol, kingRow, kingCol) == 1;
    }

    /*
     * Evaluation asymetrique : les deux camps ne poursuivent pas le meme but.
     * Les defenseurs valorisent les routes independantes, le roi mobile, l'escorte
     * et Foot-in-the-Door. Les attaquants valorisent les portes barricadees, le
     * cordon, la reduction du territoire du roi et la menace de capture.
     */
    public int evaluate(int player) {
        return evaluate(player, 0);
    }

    private int evaluate(int player, int sideToMove) {
        int winner = getWinner();
        if (winner != 0) {
            return sameTeam(winner, player) ? WIN_SCORE : -WIN_SCORE;
        }

        PositionFeatures features = collectPositionFeatures();
        int score = isDefender(player)
                ? evaluateDefenders(features, sideToMove)
                : evaluateAttackers(features, sideToMove);
        return Math.max(-WIN_SCORE + 1, Math.min(WIN_SCORE - 1, score));
    }

    int evaluateHeuristic(int player, int sideToMove) {
        return evaluate(player, sideToMove);
    }

    private int evaluate(SearchContext context, int sideToMove) {
        int winner = getWinner();
        if (winner != 0) {
            return sameTeam(winner, context.playerToHelp())
                    ? WIN_SCORE : -WIN_SCORE;
        }

        int score = context.evaluator().evaluate(
                this, context.playerToHelp(), sideToMove);
        return Math.max(-WIN_SCORE + 1, Math.min(WIN_SCORE - 1, score));
    }

    private PositionFeatures collectPositionFeatures() {
        PositionFeatures features = new PositionFeatures();

        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                int piece = grid[row][col];
                if (piece == RED) {
                    features.attackers++;
                } else if (piece == BLACK) {
                    features.defenders++;
                    int distance = manhattan(row, col, kingRow, kingCol);
                    if (distance <= 3) {
                        features.escortStrength += 4 - distance;
                    }
                }
            }
        }

        features.routes = analyzeKingRoutes();
        features.defenderMoves = countMoves(BLACK);
        features.attackerMoves = countMoves(RED);
        features.kingMoves = countMovesFrom(kingRow, kingCol, KING);
        features.blockedKingSides = countBlockedKingSides();
        features.actionableKingSides = countActionableKingCaptureSquares();
        features.kingCaptureThreat = features.blockedKingSides == 3
                && features.actionableKingSides > 0;
        features.linePressure = kingLinePressure();
        features.cornerControl = attackerCornerControl();
        features.fortifications = countDefenderFortifications();
        features.threatenedAttackers = countThreatenedSoldiers(RED);
        features.threatenedDefenders = countThreatenedSoldiers(BLACK);
        return features;
    }

    private int evaluateDefenders(PositionFeatures features, int sideToMove) {
        int phase = gamePhase(features.attackers + features.defenders);
        int materialBalance = features.defenders * 90 - features.attackers * 45;
        int shortestRoute = Math.min(9, features.routes.shortestEscapeTurns);
        int routeWeight = phase == 0 ? 170 : phase == 1 ? 240 : 310;
        int cordonPenalty = phase == 0 ? 18 : phase == 1 ? 38 : 48;
        int escortWeight = phase == 0 ? 35 : phase == 1 ? 55 : 30;
        int mobilityWeight = phase == 0 ? 5 : phase == 1 ? 7 : 9;

        int score = materialBalance;
        if (features.routes.shortestEscapeTurns == NO_ESCAPE_ROUTE) {
            score -= 5_000;
        } else {
            score += (9 - shortestRoute) * routeWeight;
        }

        score += features.routes.reachableSquares * (phase == 1 ? 5 : 3);
        score += features.routes.edgeSquaresReachable * 24;
        score += features.kingMoves * mobilityWeight;
        score += (features.defenderMoves - features.attackerMoves);
        score += features.escortStrength * escortWeight;
        score += features.fortifications * (phase == 2 ? 35 : 15);
        score += features.threatenedAttackers * 30;
        score -= features.threatenedDefenders * 65;

        score -= features.cornerControl * (phase == 0 ? 3 : phase == 1 ? 2 : 1);
        score -= features.routes.attackerBoundaryPieces * cordonPenalty;
        score -= features.blockedKingSides * (phase == 2 ? 1_350 : 850);
        score -= features.actionableKingSides * 300;
        score -= features.linePressure * 30;

        int directCorners = Integer.bitCount(features.routes.directCornerMask);
        int futureCorners = Integer.bitCount(
                features.routes.nearTermCornerMask & ~features.routes.directCornerMask);
        score += futureCorners * (phase == 0 ? 650 : 950);
        score += escapeUrgency(directCorners, sideToMove);

        if (features.kingCaptureThreat) {
            score -= captureThreatUrgency(sideToMove);
        }
        return score;
    }

    private int evaluateAttackers(PositionFeatures features, int sideToMove) {
        int phase = gamePhase(features.attackers + features.defenders);
        int materialBalance = features.attackers * 45 - features.defenders * 90;
        int shortestRoute = Math.min(9, features.routes.shortestEscapeTurns);
        int cordonWeight = phase == 0 ? 4 : phase == 1 ? 7 : 8;
        int boundaryWeight = phase == 0 ? 24 : phase == 1 ? 50 : 62;
        int blockedSideWeight = phase == 2 ? 1_600 : 1_050;

        int score = materialBalance;
        score += shortestRoute * (phase == 2 ? 300 : 220);
        score += (SQUARE_COUNT - features.routes.reachableSquares) * cordonWeight;
        score += features.routes.attackerBoundaryPieces * boundaryWeight;
        score += features.cornerControl * (phase == 0 ? 4 : phase == 1 ? 2 : 1);
        score += features.blockedKingSides * blockedSideWeight;
        score += features.actionableKingSides * 380;
        score += features.linePressure * 38;
        score += (features.attackerMoves - features.defenderMoves);
        score += features.threatenedDefenders * 75;

        score -= features.kingMoves * (phase == 0 ? 6 : phase == 1 ? 10 : 13);
        score -= features.escortStrength * (phase == 1 ? 45 : 28);
        score -= features.fortifications * 20;
        score -= features.threatenedAttackers * 35;

        int directCorners = Integer.bitCount(features.routes.directCornerMask);
        int futureCorners = Integer.bitCount(
                features.routes.nearTermCornerMask & ~features.routes.directCornerMask);
        score -= futureCorners * (phase == 0 ? 700 : 1_050);
        score -= escapeUrgency(directCorners, sideToMove);

        if (features.kingCaptureThreat) {
            score += captureThreatUrgency(sideToMove);
        }
        return score;
    }

    private int escapeUrgency(int directCorners, int sideToMove) {
        if (directCorners == 0) return 0;
        if (directCorners >= 2) return 58_000;
        if (sideToMove == BLACK) return 72_000;
        if (sideToMove == RED) return 12_000;
        return 30_000;
    }

    private int captureThreatUrgency(int sideToMove) {
        if (sideToMove == RED) return 72_000;
        if (sideToMove == BLACK) return 18_000;
        return 36_000;
    }

    // 0 = ouverture, 1 = milieu, 2 = finale pour une configuration 32 contre 16.
    private int gamePhase(int remainingSoldiers) {
        if (remainingSoldiers >= 36) return 0;
        if (remainingSoldiers >= 18) return 1;
        return 2;
    }

    public Board copy() {
        int[][] copiedGrid = new int[BOARD_SIZE][BOARD_SIZE];
        for (int row = 0; row < BOARD_SIZE; row++) {
            System.arraycopy(grid[row], 0, copiedGrid[row], 0, BOARD_SIZE);
        }
        return new Board(copiedGrid, kingRow, kingCol, zobristPiecesHash);
    }

    byte[] encodedPosition() {
        byte[] encoded = new byte[SQUARE_COUNT];
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                encoded[row * BOARD_SIZE + col] = (byte) grid[row][col];
            }
        }
        return encoded;
    }

    long piecesHash() {
        return zobristPiecesHash;
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
        if (move == null
                || !inBounds(move.fromRow, move.fromCol)
                || !inBounds(move.toRow, move.toCol)) {
            return false;
        }
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

    static int opposingSide(int player) {
        return player == RED ? BLACK : RED;
    }

    private long zobristKey(int playerToMove, int playerToHelp) {
        return zobristPiecesHash
                ^ ZOBRIST_SIDE_TO_MOVE[playerToMove]
                ^ ZOBRIST_PERSPECTIVE[playerToHelp];
    }

    private static long zobristPiece(int row, int col, int piece) {
        return ZOBRIST_PIECES[row * BOARD_SIZE + col][piece];
    }

    /*
     * BFS sur le graphe des mouvements de tour du roi. Contrairement a Manhattan,
     * il compte des tours reels et tient compte des pieces qui ferment les lignes.
     * Le plateau est statique pendant cette estimation : elle mesure la geometrie
     * actuelle, tandis que l'alpha-beta voit les ouvertures et blocages futurs.
     */
    private KingRouteAnalysis analyzeKingRoutes() {
        KingRouteAnalysis analysis = new KingRouteAnalysis();
        byte[] distance = new byte[SQUARE_COUNT];
        Arrays.fill(distance, (byte) -1);
        int[] queue = new int[SQUARE_COUNT];

        int start = kingRow * BOARD_SIZE + kingCol;
        distance[start] = 0;
        queue[0] = start;
        int head = 0;
        int tail = 1;

        while (head < tail) {
            int square = queue[head++];
            int row = square / BOARD_SIZE;
            int col = square % BOARD_SIZE;
            int currentDistance = distance[square];
            if (isCorner(row, col)) continue;

            for (int[] direction : DIRECTIONS) {
                int toRow = row + direction[0];
                int toCol = col + direction[1];

                while (inBounds(toRow, toCol) && isOpenForVirtualKing(toRow, toCol)) {
                    int target = toRow * BOARD_SIZE + toCol;
                    int nextDistance = currentDistance + 1;

                    if (distance[target] == -1) {
                        distance[target] = (byte) nextDistance;
                        queue[tail++] = target;
                    }

                    if (isCorner(toRow, toCol)) {
                        int cornerBit = 1 << cornerIndex(toRow, toCol);
                        analysis.shortestEscapeTurns = Math.min(
                                analysis.shortestEscapeTurns, nextDistance);
                        if (currentDistance == 0) {
                            analysis.directCornerMask |= cornerBit;
                        }
                        if (currentDistance <= 1) {
                            analysis.nearTermCornerMask |= cornerBit;
                        }
                    }

                    toRow += direction[0];
                    toCol += direction[1];
                }
            }
        }

        boolean[] boundaryAttackers = new boolean[SQUARE_COUNT];
        for (int square = 0; square < SQUARE_COUNT; square++) {
            if (distance[square] < 0) continue;

            analysis.reachableSquares++;
            int row = square / BOARD_SIZE;
            int col = square % BOARD_SIZE;
            if (distance[square] == 1) analysis.oneMoveSquares++;
            if (isEdge(row, col) && !isCorner(row, col)) {
                analysis.edgeSquaresReachable++;
            }

            if (distance[square] <= 1) {
                for (int[] direction : DIRECTIONS) {
                    int adjacentRow = row + direction[0];
                    int adjacentCol = col + direction[1];
                    if (inBounds(adjacentRow, adjacentCol)
                            && grid[adjacentRow][adjacentCol] == RED) {
                        boundaryAttackers[adjacentRow * BOARD_SIZE + adjacentCol] = true;
                    }
                }
            }
        }

        for (boolean boundaryAttacker : boundaryAttackers) {
            if (boundaryAttacker) analysis.attackerBoundaryPieces++;
        }
        return analysis;
    }

    private boolean isOpenForVirtualKing(int row, int col) {
        return grid[row][col] == EMPTY || (row == kingRow && col == kingCol);
    }

    private int countMovesFrom(int fromRow, int fromCol, int piece) {
        int count = 0;
        for (int[] direction : DIRECTIONS) {
            int row = fromRow + direction[0];
            int col = fromCol + direction[1];
            while (inBounds(row, col) && grid[row][col] == EMPTY) {
                if (piece == KING || !isHostileSquare(row, col)) count++;
                row += direction[0];
                col += direction[1];
            }
        }
        return count;
    }

    private int countBlockedKingSides() {
        int blocked = 0;
        for (int[] direction : DIRECTIONS) {
            if (isBlockedForKing(
                    kingRow + direction[0], kingCol + direction[1])) {
                blocked++;
            }
        }
        return blocked;
    }

    private int countActionableKingCaptureSquares() {
        int actionable = 0;
        for (int[] direction : DIRECTIONS) {
            int row = kingRow + direction[0];
            int col = kingCol + direction[1];
            if (!inBounds(row, col) || grid[row][col] != EMPTY
                    || isHostileSquare(row, col)) {
                continue;
            }
            if (canSideMoveTo(RED, row, col, true)) actionable++;
        }
        return actionable;
    }

    /*
     * Cherche une piece pouvant atteindre la case cible. Quand
     * preserveKingBlockers est vrai, un attaquant deja colle au roi n'est pas
     * considere : le deplacer ouvrirait simultanement un autre cote.
     */
    private boolean canSideMoveTo(int player, int targetRow, int targetCol,
                                  boolean preserveKingBlockers) {
        if (!inBounds(targetRow, targetCol) || grid[targetRow][targetCol] != EMPTY) {
            return false;
        }
        if (isAttacker(player) && isHostileSquare(targetRow, targetCol)) {
            return false;
        }

        for (int[] direction : DIRECTIONS) {
            int row = targetRow + direction[0];
            int col = targetCol + direction[1];
            while (inBounds(row, col) && grid[row][col] == EMPTY) {
                row += direction[0];
                col += direction[1];
            }
            if (!inBounds(row, col)) continue;

            int piece = grid[row][col];
            boolean belongsToSide = isAttacker(player)
                    ? piece == RED
                    : piece == BLACK || piece == KING;
            if (!belongsToSide) continue;
            if (isHostileSquare(targetRow, targetCol) && piece != KING) continue;
            if (preserveKingBlockers && piece == RED
                    && manhattan(row, col, kingRow, kingCol) == 1) {
                continue;
            }
            return true;
        }
        return false;
    }

    private int kingLinePressure() {
        int pressure = 0;
        for (int[] direction : DIRECTIONS) {
            int row = kingRow + direction[0];
            int col = kingCol + direction[1];
            int distance = 1;
            while (inBounds(row, col) && grid[row][col] == EMPTY) {
                row += direction[0];
                col += direction[1];
                distance++;
            }
            if (!inBounds(row, col)) continue;

            if (grid[row][col] == RED) {
                pressure += Math.max(1, BOARD_SIZE + 1 - distance);
            } else if (grid[row][col] == BLACK) {
                pressure -= Math.max(1, 8 - distance);
            }
        }
        return pressure;
    }

    /*
     * Valeur positive quand les attaquants possedent ou peuvent prendre les
     * deux portes d'un coin; negative quand les defenseurs ont deja mis le pied
     * dans la porte. Les cases a deux pas servent de soutien mais pesent moins.
     */
    private int attackerCornerControl() {
        int control = 0;

        for (int corner = 0; corner < CORNERS.length; corner++) {
            int occupiedGateways = 0;
            int cornerRow = CORNERS[corner][0];
            int cornerCol = CORNERS[corner][1];

            for (int[] gateway : CORNER_GATEWAYS[corner]) {
                int row = gateway[0];
                int col = gateway[1];
                int piece = grid[row][col];
                if (piece == RED) {
                    control += 110;
                    occupiedGateways++;
                } else if (piece == BLACK || piece == KING) {
                    control -= 95;
                } else {
                    if (canSideMoveTo(RED, row, col, false)) control += 20;
                    if (canSideMoveTo(BLACK, row, col, false)) control -= 15;
                }

                int supportRow = cornerRow + 2 * Integer.signum(row - cornerRow);
                int supportCol = cornerCol + 2 * Integer.signum(col - cornerCol);
                int support = grid[supportRow][supportCol];
                if (support == RED) control += 24;
                else if (support == BLACK || support == KING) control -= 18;
            }

            if (occupiedGateways == 2) control += 140;
        }
        return control;
    }

    private int countDefenderFortifications() {
        int fortifications = 0;
        for (int row = 0; row < BOARD_SIZE - 1; row++) {
            for (int col = 0; col < BOARD_SIZE - 1; col++) {
                if (grid[row][col] == BLACK
                        && grid[row + 1][col] == BLACK
                        && grid[row][col + 1] == BLACK
                        && grid[row + 1][col + 1] == BLACK) {
                    fortifications++;
                }
            }
        }
        return fortifications;
    }

    private int countThreatenedSoldiers(int victimPiece) {
        int capturingSide = victimPiece == RED ? BLACK : RED;
        int representativePiece = capturingSide == RED ? RED : BLACK;
        int threatened = 0;

        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                if (grid[row][col] != victimPiece) continue;

                boolean canBeCaptured = false;
                for (int[] direction : DIRECTIONS) {
                    int landingRow = row + direction[0];
                    int landingCol = col + direction[1];
                    int supportRow = row - direction[0];
                    int supportCol = col - direction[1];

                    if (!inBounds(landingRow, landingCol)
                            || grid[landingRow][landingCol] != EMPTY
                            || !shouldCapture(supportRow, supportCol, representativePiece)) {
                        continue;
                    }

                    if (canSideMoveTo(capturingSide, landingRow, landingCol, false)) {
                        canBeCaptured = true;
                        break;
                    }
                }
                if (canBeCaptured) threatened++;
            }
        }
        return threatened;
    }

    private int closestCornerManhattan(int row, int col) {
        int closest = Integer.MAX_VALUE;
        for (int[] corner : CORNERS) {
            closest = Math.min(closest, manhattan(row, col, corner[0], corner[1]));
        }
        return closest;
    }

    private int cornerGatewayValue(int row, int col) {
        for (int corner = 0; corner < CORNERS.length; corner++) {
            int cornerRow = CORNERS[corner][0];
            int cornerCol = CORNERS[corner][1];
            for (int[] gateway : CORNER_GATEWAYS[corner]) {
                if (row == gateway[0] && col == gateway[1]) return 3;

                int supportRow = cornerRow
                        + 2 * Integer.signum(gateway[0] - cornerRow);
                int supportCol = cornerCol
                        + 2 * Integer.signum(gateway[1] - cornerCol);
                if (row == supportRow && col == supportCol) return 1;
            }
        }
        return 0;
    }

    private int cornerIndex(int row, int col) {
        if (row == 0) return col == 0 ? 0 : 1;
        return col == 0 ? 2 : 3;
    }

    private boolean isEdge(int row, int col) {
        return row == 0 || row == BOARD_SIZE - 1
                || col == 0 || col == BOARD_SIZE - 1;
    }

    private int manhattan(int firstRow, int firstCol, int secondRow, int secondCol) {
        return Math.abs(firstRow - secondRow) + Math.abs(firstCol - secondCol);
    }

    private static final class PositionFeatures {
        int attackers;
        int defenders;
        int defenderMoves;
        int attackerMoves;
        int kingMoves;
        int blockedKingSides;
        int actionableKingSides;
        int linePressure;
        int cornerControl;
        int escortStrength;
        int fortifications;
        int threatenedAttackers;
        int threatenedDefenders;
        boolean kingCaptureThreat;
        KingRouteAnalysis routes;
    }

    private static final class KingRouteAnalysis {
        int shortestEscapeTurns = NO_ESCAPE_ROUTE;
        int reachableSquares;
        int oneMoveSquares;
        int edgeSquaresReachable;
        int attackerBoundaryPieces;
        int directCornerMask;
        int nearTermCornerMask;
    }
}
