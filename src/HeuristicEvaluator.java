final class HeuristicEvaluator implements PositionEvaluator {
    static final HeuristicEvaluator INSTANCE = new HeuristicEvaluator();

    private HeuristicEvaluator() {}

    @Override
    public int evaluate(Board board, int perspective, int sideToMove) {
        return board.evaluateHeuristic(perspective, sideToMove);
    }
}
