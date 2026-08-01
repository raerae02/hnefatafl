public class DepthTest {
    public static void main(String[] args) {
        // position initiale (dense, pire cas pour la profondeur)
        Board board = new Board(Simulation.initialGrid());
        for (int side : new int[]{Board.RED, Board.BLACK}) {
            long start = System.currentTimeMillis();
            Move move = board.getBestMoveTimed(side, 3500, new java.util.HashMap<>());
            long elapsed = System.currentTimeMillis() - start;
            System.out.println((side == Board.RED ? "ROUGE" : "NOIR ") + " initiale : profondeur "
                    + Board.lastSearchDepth + " en " + elapsed + " ms, coup " + move);
        }
    }
}
