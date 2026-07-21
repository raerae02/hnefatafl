/** Immutable masks and lookup tables shared by move generation and evaluation. */
final class BoardGeometry {
    static final BitBoard169.PositionBits BOARD = maskFor((r, c) -> true);
    static final BitBoard169.PositionBits CORNERS = maskFor((r, c) -> (r == 0 || r == 12) && (c == 0 || c == 12));
    static final BitBoard169.PositionBits THRONE = maskFor((r, c) -> r == 6 && c == 6);
    static final BitBoard169.PositionBits EDGES = maskFor((r, c) -> r == 0 || r == 12 || c == 0 || c == 12);
    static final BitBoard169.PositionBits KING_APPROACH = maskFor((r, c) -> Math.abs(r - 6) + Math.abs(c - 6) == 1);
    static final int[][] NEIGHBORS = new int[169][4];
    static final int[][] TWO_AWAY = new int[169][4];
    static final int[][] DISTANCE_TO_CORNERS = new int[169][4];
    static final int[][] CORNER_APPROACH_PATHS = buildCornerApproachPaths();

    static {
        for (int square = 0; square < 169; square++) {
            int row = BitBoard169.row(square), col = BitBoard169.col(square);
            for (int d = 0; d < 4; d++) {
                NEIGHBORS[square][d] = squareAt(row + Board.DIRECTIONS[d][0], col + Board.DIRECTIONS[d][1]);
                TWO_AWAY[square][d] = squareAt(row + 2 * Board.DIRECTIONS[d][0], col + 2 * Board.DIRECTIONS[d][1]);
            }
            for (int corner = 0; corner < 4; corner++)
                DISTANCE_TO_CORNERS[square][corner] = Math.abs(row - Board.CORNERS[corner][0])
                        + Math.abs(col - Board.CORNERS[corner][1]);
        }
    }

    private BoardGeometry() {}

    private static int[][] buildCornerApproachPaths() {
        int[][] paths = new int[4][24];
        for (int corner = 0; corner < 4; corner++) {
            int row = Board.CORNERS[corner][0], col = Board.CORNERS[corner][1], index = 0;
            int rowStep = row == 0 ? 1 : -1, colStep = col == 0 ? 1 : -1;
            for (int i = 1; i < 13; i++) paths[corner][index++] = BitBoard169.square(row + rowStep * i, col);
            for (int i = 1; i < 13; i++) paths[corner][index++] = BitBoard169.square(row, col + colStep * i);
        }
        return paths;
    }

    private static int squareAt(int row, int col) {
        return row < 0 || row >= 13 || col < 0 || col >= 13 ? -1 : BitBoard169.square(row, col);
    }

    private static BitBoard169.PositionBits maskFor(SquarePredicate predicate) {
        long[] segments = new long[3];
        for (int row = 0; row < 13; row++) for (int col = 0; col < 13; col++) {
            if (!predicate.test(row, col)) continue;
            int square = BitBoard169.square(row, col);
            segments[square >>> 6] |= 1L << (square & 63);
        }
        return BitBoard169.bits(segments[0], segments[1], segments[2]);
    }

    private interface SquarePredicate { boolean test(int row, int col); }
}
