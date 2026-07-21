import java.util.ArrayList;
import java.util.List;

class BoardEvaluator {
    private static final int WIN_SCORE = 1000000;

    private final Board board;
    private final int[][] grid;
    private final int[] moveBuffer;
    private final int[] replyBuffer;

    BoardEvaluator(Board board) {
        this.board = board;
        this.grid = board.grid;
        this.moveBuffer = board.evaluationMoveBuffer();
        this.replyBuffer = board.evaluationReplyBuffer();
    }

    int evaluate(int cpuPlayer) {
        int winner = board.getWinner();
        if (winner == cpuPlayer) {
            return WIN_SCORE;
        }
        if (winner != 0) {
            return -WIN_SCORE;
        }

        RouteMetrics routes = analyzeKingRoutes();
        TacticalEscapeMetrics tactical = analyzeUnblockableEscapes(cpuPlayer);
        int redScore = weightedRedScore(routes, tactical);
        int blackScore = weightedBlackScore(routes, tactical);

        if (cpuPlayer == Board.RED) {
            return redScore - blackScore;
        }
        return blackScore - redScore;
    }

    private int weightedRedScore(RouteMetrics routes, TacticalEscapeMetrics tactical) {
        return evaluateMaterialForRed()
                + evaluateKingPressure()
                + evaluateCornerControl()
                + evaluateKingEscapeBlockers()
                + evaluateRedFrontLines()
                + evaluateEdgeEscapeContainment()
                + evaluateCapturePotential(Board.RED)
                + evaluateMobility(Board.RED)
                + routes.redMeanDistance()
                + routes.redDistanceVariance()
                + tactical.redUnblockableEscapePenalty()
                + tactical.redInterceptionMobility();
    }

    private int weightedBlackScore(RouteMetrics routes, TacticalEscapeMetrics tactical) {
        return evaluateMaterialForBlack()
                + evaluateKingEscape()
                + evaluateKingSafety()
                + evaluateKingSupport()
                + evaluateDefenderCorridors()
                + evaluateEdgeEscapeThreat()
                + evaluateCapturePotential(Board.BLACK)
                + evaluateMobility(Board.BLACK)
                + routes.blackMeanDistance()
                + routes.blackDistanceVariance()
                + tactical.blackUnblockableEscapeBonus()
                + tactical.blackInterceptionMobility();
    }

    /** KDV across the two rook routes to each corner: eight routes in total. */
    private RouteMetrics analyzeKingRoutes() {
        long total = 0;
        long squaredTotal = 0;
        for (int[] corner : Board.CORNERS) {
            int viaEdgeRow = routeCost(kingRow(), kingCol(), corner[0], kingCol(), corner[0], corner[1]);
            int viaEdgeCol = routeCost(kingRow(), kingCol(), kingRow(), corner[1], corner[0], corner[1]);
            total += viaEdgeRow + viaEdgeCol;
            squaredTotal += (long) viaEdgeRow * viaEdgeRow + (long) viaEdgeCol * viaEdgeCol;
        }
        int mean = (int) (total / 8);
        int variance = (int) (squaredTotal / 8 - (long) mean * mean);
        int meanScore = mean * 150;
        int varianceScore = variance * 25;
        // RED wants a high mean and low variance; BLACK wants the inverse.
        return new RouteMetrics(meanScore, -varianceScore, -meanScore, varianceScore);
    }

    private int routeCost(int startRow, int startCol, int viaRow, int viaCol, int cornerRow, int cornerCol) {
        return segmentCost(startRow, startCol, viaRow, viaCol)
                + segmentCost(viaRow, viaCol, cornerRow, cornerCol);
    }

    private int segmentCost(int fromRow, int fromCol, int toRow, int toCol) {
        if (fromRow == toRow && fromCol == toCol) return 0;
        int rowStep = Integer.signum(toRow - fromRow);
        int colStep = Integer.signum(toCol - fromCol);
        int cost = 0;
        int row = fromRow + rowStep;
        int col = fromCol + colStep;
        while (row != toRow || col != toCol) {
            cost += kdvSquareCost(row, col);
            row += rowStep;
            col += colStep;
        }
        return cost + kdvSquareCost(toRow, toCol);
    }

    private int kdvSquareCost(int row, int col) {
        int piece = grid[row][col];
        if (piece == Board.EMPTY) return 1;
        if (piece == Board.BLACK || piece == Board.KING) return 2;
        return 4;
    }

    /**
     * A two-ply forced escape: BLACK moves the king, RED replies in every legal
     * possible way, and BLACK still has an immediate corner move. This is only
     * meaningful when BLACK is the side to move at the evaluated leaf.
     */
    private TacticalEscapeMetrics analyzeUnblockableEscapes(int sideToMove) {
        if (sideToMove != Board.BLACK) return TacticalEscapeMetrics.NONE;
        int forcedEscapes = 0;
        int stoppingReplies = 0;
        int blackMoveCount = board.getLegalPackedMoves(Board.BLACK, moveBuffer);
        SearchUndo primaryUndo = board.evaluationPrimaryUndo();
        SearchUndo replyUndo = board.evaluationReplyUndo();

        for (int i = 0; i < blackMoveCount; i++) {
            int blackMove = moveBuffer[i];
            int from = PackedMove.from(blackMove);
            if (board.getPiece(BitBoard169.row(from), BitBoard169.col(from)) != Board.KING) continue;
            int target = PackedMove.to(blackMove);
            if (!isEdgeSquare(BitBoard169.row(target), BitBoard169.col(target))) continue;

            board.makeMovePacked(blackMove, primaryUndo);
            boolean forced = board.getWinner() == Board.BLACK;
            if (!forced && canKingWinInOneMove()) {
                int redMoveCount = board.getLegalPackedMoves(Board.RED, replyBuffer);
                boolean canStop = false;
                int stopsForThisThreat = 0;
                for (int reply = 0; reply < redMoveCount; reply++) {
                    int redMove = replyBuffer[reply];
                    board.makeMovePacked(redMove, replyUndo);
                    boolean stops = board.getWinner() == Board.RED || !canKingWinInOneMove();
                    board.unmakeMovePacked(redMove, replyUndo);
                    if (stops) {
                        canStop = true;
                        stopsForThisThreat++;
                    }
                }
                forced = !canStop;
                stoppingReplies += Math.min(8, stopsForThisThreat);
            }
            board.unmakeMovePacked(blackMove, primaryUndo);
            if (forced) forcedEscapes++;
        }

        int escapeScore = forcedEscapes * 70_000;
        int interceptionScore = stoppingReplies * 500;
        return new TacticalEscapeMetrics(-escapeScore, interceptionScore,
                escapeScore, -interceptionScore);
    }

    private record RouteMetrics(int redMeanDistance, int redDistanceVariance,
                                int blackMeanDistance, int blackDistanceVariance) {}

    private record TacticalEscapeMetrics(int redUnblockableEscapePenalty, int redInterceptionMobility,
                                          int blackUnblockableEscapeBonus, int blackInterceptionMobility) {
        static final TacticalEscapeMetrics NONE = new TacticalEscapeMetrics(0, 0, 0, 0);
    }

    private boolean isEdgeSquare(int row, int col) {
        return row == 0 || row == Board.BOARD_SIZE - 1 || col == 0 || col == Board.BOARD_SIZE - 1;
    }

    private int evaluateMaterialForRed() {
        return board.countPieces(Board.RED) * 20;
    }

    private int evaluateMaterialForBlack() {
        return board.countPieces(Board.BLACK) * 30;
    }

    private int evaluateKingEscape() {
        int score = 0;

        int distance = minDistanceToCorner(kingRow(), kingCol());
        score += (24 - distance) * 35;

        int openLines = countOpenKingLines();
        score += openLines * 2500;

        if (canKingWinInOneMove()) {
            score += 50000;
        }

        if (distance <= 3) {
            score += 2500;
        }

        if (isKingOnBoardEdge()) {
            score += 1200;
        }

        if (openLines > 0) {
            score += 4000;
        }

        return score;
    }

    private int evaluateKingSafety() {
        int score = 0;
        int blockedSides = countBlockedKingSides();

        if (blockedSides == 0) {
            score += 500;
        } else if (blockedSides == 1) {
            score += 100;
        } else if (blockedSides == 2) {
            score -= 700;
        } else if (blockedSides == 3) {
            score -= 4000;
        } else {
            score -= 100000;
        }

        score -= countAttackersNearKing() * 300;
        score += countAdjacentDefendersToKing() * 350;
        score += countKingMobility() * 120;
        return score;
    }

    private int evaluateKingPressure() {
        int score = 0;

        int blockedSides = countBlockedKingSides();
        score += blockedSides * 1000;
        score += countAttackersNearKing() * 350;

        if (countOpenKingLines() > 0) {
            score -= 5000;
        }

        if (canKingWinInOneMove()) {
            score -= 60000;
        }

        if (blockedSides >= 3) {
            score += 5000;
        }

        return score;
    }

    private int evaluateMobility(int player) {
        return board.getLegalPackedMoves(player, moveBuffer) * 2;
    }

    private int evaluateCapturePotential(int player) {
        int score = 0;

        int count = board.getLegalPackedMoves(player, moveBuffer);
        for (int i = 0; i < count; i++) {
            int captured = board.countCapturesIfPacked(moveBuffer[i]);

            if (captured > 0) {
                score += captured * 500;
            }
        }

        return score;
    }

    private int evaluateCornerControl() {
        int score = 0;

        for (int[] corner : Board.CORNERS) {
            if (isControlledByRed(corner[0], corner[1])) {
                score += 900;
            }
        }

        if (countOpenKingLines() > 0) {
            score -= 4000;
        }

        return score;
    }

    private int evaluateKingEscapeBlockers() {
        int score = 0;

        score += countPiecesOnKingLines(Board.RED) * 450;

        int distance = minDistanceToCorner(kingRow(), kingCol());
        if (distance <= 4) {
            score += countRedControlledCornersNearKing() * 1200;
        }

        return score;
    }

    private int evaluateEdgeEscapeContainment() {
        int score = 0;

        if (!isKingOnBoardEdge()) {
            return score;
        }

        score += countRedOccupiedCriticalEscapeSquares() * 14000;
        score += countRedControlledCriticalEscapeSquares() * 500;
        score -= countOpenCriticalEscapeSquares() * 12000;

        if (minDistanceToCorner(kingRow(), kingCol()) <= 2) {
            score -= countOpenCriticalEscapeSquares() * 18000;
        }

        return score;
    }

    private int evaluateRedFrontLines() {
        int score = 0;

        for (int[] corner : Board.CORNERS) {
            int blockers = countRedBlockersBetweenKingAndCorner(corner[0], corner[1]);
            int openSquares = countOpenSquaresBetweenKingAndCorner(corner[0], corner[1]);
            int distance = distanceManhattan(kingRow(), kingCol(), corner[0], corner[1]);

            score += blockers * 2500;
            score -= openSquares * 300;

            if (distance <= 6) {
                score += blockers * 2500;
                score -= openSquares * 800;
            }
        }

        score += countRedPiecesCloserToCornersThanKing() * 800;
        score += countRedOccupiedApproachSquares() * 9000;
        score -= countCornersWithClearApproachFromKing() * 7000;

        return score;
    }

    private int evaluateDefenderCorridors() {
        int score = 0;

        for (int[] corner : Board.CORNERS) {
            int blockers = countRedBlockersBetweenKingAndCorner(corner[0], corner[1]);
            int openSquares = countOpenSquaresBetweenKingAndCorner(corner[0], corner[1]);
            int distance = distanceManhattan(kingRow(), kingCol(), corner[0], corner[1]);

            score -= blockers * 2500;
            score += openSquares * 300;

            if (distance <= 6) {
                score -= blockers * 2500;
                score += openSquares * 800;
            }
        }

        score -= countRedPiecesCloserToCornersThanKing() * 800;
        score -= countRedOccupiedApproachSquares() * 9000;
        score += countCornersWithClearApproachFromKing() * 7000;

        return score;
    }

    private int evaluateEdgeEscapeThreat() {
        int score = 0;

        if (!isKingOnBoardEdge()) {
            return score;
        }

        score += countOpenCriticalEscapeSquares() * 12000;
        score -= countRedOccupiedCriticalEscapeSquares() * 14000;
        score -= countRedControlledCriticalEscapeSquares() * 500;

        if (minDistanceToCorner(kingRow(), kingCol()) <= 2) {
            score += countOpenCriticalEscapeSquares() * 18000;
        }

        return score;
    }

    private int evaluateKingSupport() {
        int score = 0;

        score += countAdjacentDefendersToKing() * 500;
        score += countPiecesOnKingLines(Board.BLACK) * 250;

        if (countBlockedKingSides() >= 3) {
            score -= 3000;
        }

        return score;
    }

    private int countAttackersNearKing() {
        int attackers = 0;

        for (int row = kingRow() - 2; row <= kingRow() + 2; row++) {
            for (int col = kingCol() - 2; col <= kingCol() + 2; col++) {
                if (!inBounds(row, col)) continue;
                if (grid[row][col] == Board.RED) attackers++;
            }
        }

        return attackers;
    }

    private int countAdjacentDefendersToKing() {
        int defenders = 0;

        for (int[] direction : Board.DIRECTIONS) {
            int row = kingRow() + direction[0];
            int col = kingCol() + direction[1];

            if (inBounds(row, col) && grid[row][col] == Board.BLACK) {
                defenders++;
            }
        }

        return defenders;
    }

    private int countKingMobility() {
        int mobility = 0;

        for (int[] direction : Board.DIRECTIONS) {
            int row = kingRow() + direction[0];
            int col = kingCol() + direction[1];

            while (inBounds(row, col) && grid[row][col] == Board.EMPTY) {
                mobility++;
                row += direction[0];
                col += direction[1];
            }
        }

        return mobility;
    }

    private boolean canKingWinInOneMove() {
        for (int[] corner : Board.CORNERS) {
            Move move = new Move(kingRow(), kingCol(), corner[0], corner[1]);
            if (board.isValidMove(move)) {
                return true;
            }
        }

        return false;
    }

    private int countPiecesOnKingLines(int player) {
        int count = 0;

        for (int[] direction : Board.DIRECTIONS) {
            int row = kingRow() + direction[0];
            int col = kingCol() + direction[1];

            while (inBounds(row, col)) {
                if (player == Board.RED && grid[row][col] == Board.RED) {
                    count++;
                } else if (player == Board.BLACK && grid[row][col] == Board.BLACK) {
                    count++;
                }

                row += direction[0];
                col += direction[1];
            }
        }

        return count;
    }

    private int countRedControlledCornersNearKing() {
        int count = 0;

        for (int[] corner : Board.CORNERS) {
            int distance = distanceManhattan(kingRow(), kingCol(), corner[0], corner[1]);
            if (distance <= 6 && isControlledByRed(corner[0], corner[1])) {
                count++;
            }
        }

        return count;
    }

    private int countRedBlockersBetweenKingAndCorner(int cornerRow, int cornerCol) {
        int count = 0;

        if (kingRow() == cornerRow) {
            int step = Integer.signum(cornerCol - kingCol());
            for (int col = kingCol() + step; col != cornerCol + step; col += step) {
                if (grid[kingRow()][col] == Board.RED) count++;
            }
        }

        if (kingCol() == cornerCol) {
            int step = Integer.signum(cornerRow - kingRow());
            for (int row = kingRow() + step; row != cornerRow + step; row += step) {
                if (grid[row][kingCol()] == Board.RED) count++;
            }
        }

        if (kingRow() != cornerRow && kingCol() != cornerCol) {
            int rowStep = Integer.signum(cornerRow - kingRow());
            int colStep = Integer.signum(cornerCol - kingCol());
            if (grid[cornerRow][kingCol()] == Board.RED) count++;
            if (grid[kingRow()][cornerCol] == Board.RED) count++;

            for (int row = kingRow() + rowStep; row != cornerRow + rowStep; row += rowStep) {
                if (grid[row][kingCol()] == Board.RED) count++;
            }
            for (int col = kingCol() + colStep; col != cornerCol + colStep; col += colStep) {
                if (grid[kingRow()][col] == Board.RED) count++;
            }
        }

        return count;
    }

    private int countOpenSquaresBetweenKingAndCorner(int cornerRow, int cornerCol) {
        int count = 0;

        if (kingRow() == cornerRow) {
            int step = Integer.signum(cornerCol - kingCol());
            for (int col = kingCol() + step; col != cornerCol + step; col += step) {
                if (grid[kingRow()][col] == Board.EMPTY) count++;
            }
        }

        if (kingCol() == cornerCol) {
            int step = Integer.signum(cornerRow - kingRow());
            for (int row = kingRow() + step; row != cornerRow + step; row += step) {
                if (grid[row][kingCol()] == Board.EMPTY) count++;
            }
        }

        if (kingRow() != cornerRow && kingCol() != cornerCol) {
            int rowStep = Integer.signum(cornerRow - kingRow());
            int colStep = Integer.signum(cornerCol - kingCol());

            for (int row = kingRow() + rowStep; row != cornerRow + rowStep; row += rowStep) {
                if (grid[row][kingCol()] == Board.EMPTY) count++;
            }
            for (int col = kingCol() + colStep; col != cornerCol + colStep; col += colStep) {
                if (grid[kingRow()][col] == Board.EMPTY) count++;
            }
        }

        return count;
    }

    private int countCornersWithClearApproachFromKing() {
        int count = 0;

        for (int[] corner : Board.CORNERS) {
            if (hasClearApproachToCorner(corner[0], corner[1])) {
                count++;
            }
        }

        return count;
    }

    private int countRedOccupiedApproachSquares() {
        int count = 0;

        if (kingRow() != 0 && grid[0][kingCol()] == Board.RED) count++;
        if (kingRow() != Board.BOARD_SIZE - 1 && grid[Board.BOARD_SIZE - 1][kingCol()] == Board.RED) count++;
        if (kingCol() != 0 && grid[kingRow()][0] == Board.RED) count++;
        if (kingCol() != Board.BOARD_SIZE - 1 && grid[kingRow()][Board.BOARD_SIZE - 1] == Board.RED) count++;

        return count;
    }

    private boolean hasClearApproachToCorner(int cornerRow, int cornerCol) {
        if (kingRow() == cornerRow || kingCol() == cornerCol) {
            Move move = new Move(kingRow(), kingCol(), cornerRow, cornerCol);
            return board.isValidMove(move);
        }

        return isPathClearFromKingTo(cornerRow, kingCol())
                || isPathClearFromKingTo(kingRow(), cornerCol);
    }

    private boolean isPathClearFromKingTo(int targetRow, int targetCol) {
        if (kingRow() != targetRow && kingCol() != targetCol) {
            return false;
        }

        int rowDirection = Integer.signum(targetRow - kingRow());
        int colDirection = Integer.signum(targetCol - kingCol());
        int row = kingRow() + rowDirection;
        int col = kingCol() + colDirection;

        while (row != targetRow || col != targetCol) {
            if (grid[row][col] != Board.EMPTY) return false;
            row += rowDirection;
            col += colDirection;
        }

        return grid[targetRow][targetCol] == Board.EMPTY || isCorner(targetRow, targetCol);
    }

    private int countRedPiecesCloserToCornersThanKing() {
        int count = 0;

        for (int row = 0; row < Board.BOARD_SIZE; row++) {
            for (int col = 0; col < Board.BOARD_SIZE; col++) {
                if (grid[row][col] != Board.RED) continue;

                for (int[] corner : Board.CORNERS) {
                    int redDistance = distanceManhattan(row, col, corner[0], corner[1]);
                    int kingDistance = distanceManhattan(kingRow(), kingCol(), corner[0], corner[1]);
                    if (redDistance < kingDistance) {
                        count++;
                        break;
                    }
                }
            }
        }

        return count;
    }

    private int countOpenCriticalEscapeSquares() {
        int count = 0;

        for (int[] square : getCriticalEscapeSquares()) {
            if (grid[square[0]][square[1]] == Board.EMPTY) {
                count++;
            }
        }

        return count;
    }

    private int countRedOccupiedCriticalEscapeSquares() {
        int count = 0;

        for (int[] square : getCriticalEscapeSquares()) {
            if (grid[square[0]][square[1]] == Board.RED) {
                count++;
            }
        }

        return count;
    }

    private int countRedControlledCriticalEscapeSquares() {
        int count = 0;

        for (int[] square : getCriticalEscapeSquares()) {
            int row = square[0];
            int col = square[1];

            if (grid[row][col] != Board.RED && isControlledByRed(row, col)) {
                count++;
            }
        }

        return count;
    }

    private List<int[]> getCriticalEscapeSquares() {
        List<int[]> squares = new ArrayList<>();
        int edge = Board.BOARD_SIZE - 1;

        if (kingRow() == 0) {
            addSquaresBetweenKingAndCorner(squares, 0, 0);
            addSquaresBetweenKingAndCorner(squares, 0, edge);
        } else if (kingRow() == edge) {
            addSquaresBetweenKingAndCorner(squares, edge, 0);
            addSquaresBetweenKingAndCorner(squares, edge, edge);
        }

        if (kingCol() == 0) {
            addSquaresBetweenKingAndCorner(squares, 0, 0);
            addSquaresBetweenKingAndCorner(squares, edge, 0);
        } else if (kingCol() == edge) {
            addSquaresBetweenKingAndCorner(squares, 0, edge);
            addSquaresBetweenKingAndCorner(squares, edge, edge);
        }

        return squares;
    }

    private void addSquaresBetweenKingAndCorner(List<int[]> squares, int cornerRow, int cornerCol) {
        if (kingRow() != cornerRow && kingCol() != cornerCol) {
            return;
        }

        int rowDirection = Integer.signum(cornerRow - kingRow());
        int colDirection = Integer.signum(cornerCol - kingCol());
        int row = kingRow() + rowDirection;
        int col = kingCol() + colDirection;

        while (row != cornerRow || col != cornerCol) {
            addUniqueSquare(squares, row, col);
            row += rowDirection;
            col += colDirection;
        }

        addUniqueSquare(squares, cornerRow, cornerCol);
    }

    private void addUniqueSquare(List<int[]> squares, int row, int col) {
        for (int[] square : squares) {
            if (square[0] == row && square[1] == col) {
                return;
            }
        }

        squares.add(new int[]{row, col});
    }

    private boolean isControlledByRed(int row, int col) {
        for (int[] direction : Board.DIRECTIONS) {
            int r = row + direction[0];
            int c = col + direction[1];

            while (inBounds(r, c)) {
                if (grid[r][c] == Board.RED) return true;
                if (grid[r][c] != Board.EMPTY) break;
                r += direction[0];
                c += direction[1];
            }
        }

        return false;
    }

    private boolean isKingOnBoardEdge() {
        return kingRow() == 0 || kingRow() == Board.BOARD_SIZE - 1
                || kingCol() == 0 || kingCol() == Board.BOARD_SIZE - 1;
    }

    private int countBlockedKingSides() {
        int blocked = 0;
        if (isBlockedForKing(kingRow() - 1, kingCol())) blocked++;
        if (isBlockedForKing(kingRow() + 1, kingCol())) blocked++;
        if (isBlockedForKing(kingRow(), kingCol() - 1)) blocked++;
        if (isBlockedForKing(kingRow(), kingCol() + 1)) blocked++;
        return blocked;
    }

    private boolean isBlockedForKing(int row, int col) {
        if (!inBounds(row, col)) return true;
        if (isCorner(row, col)) return true;
        if (isThrone(row, col)) return true;
        return grid[row][col] == Board.RED;
    }

    private int countOpenKingLines() {
        int openLines = 0;

        for (int[] direction : Board.DIRECTIONS) {
            if (isPathOpenForKing(direction[0], direction[1])) {
                openLines++;
            }
        }

        return openLines;
    }

    private boolean isPathOpenForKing(int dr, int dc) {
        int row = kingRow() + dr;
        int col = kingCol() + dc;

        while (inBounds(row, col)) {
            if (grid[row][col] != Board.EMPTY) return false;
            if (isCorner(row, col)) return true;
            row += dr;
            col += dc;
        }

        return false;
    }

    private int minDistanceToCorner(int row, int col) {
        int best = Integer.MAX_VALUE;

        for (int[] corner : Board.CORNERS) {
            int distance = distanceManhattan(row, col, corner[0], corner[1]);
            best = Math.min(best, distance);
        }

        return best;
    }

    private int distanceManhattan(int r1, int c1, int r2, int c2) {
        return Math.abs(r1 - r2) + Math.abs(c1 - c2);
    }

    private boolean isCorner(int row, int col) {
        return (row == 0 || row == Board.BOARD_SIZE - 1)
                && (col == 0 || col == Board.BOARD_SIZE - 1);
    }

    private boolean isThrone(int row, int col) {
        return row == Board.THRONE_ROW && col == Board.THRONE_COL;
    }

    private boolean inBounds(int row, int col) {
        return row >= 0 && row < Board.BOARD_SIZE && col >= 0 && col < Board.BOARD_SIZE;
    }

    private int kingRow() {
        return board.getKingRow();
    }

    private int kingCol() {
        return board.getKingCol();
    }
}
