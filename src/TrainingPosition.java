final class TrainingPosition {
    private static final int[] SERVER_OPENING = {
            0, 0, 0, 0, 4, 4, 4, 4, 4, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0,
            4, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 4,
            4, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 4,
            4, 4, 0, 2, 2, 2, 5, 2, 2, 2, 0, 4, 4,
            4, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 4,
            4, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 4,
            0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 4, 4, 4, 4, 4, 0, 0, 0, 0
    };

    private TrainingPosition() {}

    static Board serverOpening() {
        int[][] grid = new int[Board.BOARD_SIZE][Board.BOARD_SIZE];
        for (int square = 0; square < SERVER_OPENING.length; square++) {
            grid[square / Board.BOARD_SIZE][square % Board.BOARD_SIZE] =
                    SERVER_OPENING[square];
        }
        return new Board(grid);
    }
}
