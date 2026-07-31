import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ModelProbeMain {
    private ModelProbeMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ModelProbeMain model.hnn");
        }
        NnueEvaluator evaluator = NnueEvaluator.load(Path.of(args[0]));
        Board board = TrainingPosition.serverOpening();
        int defenderScore = evaluator.evaluate(board, Board.BLACK, Board.RED);
        int attackerScore = evaluator.evaluate(board, Board.RED, Board.RED);
        List<Move> legalMoves = board.getLegalMoves(Board.RED);
        Map<Move, Float> policy = evaluator.rootPolicyScores(
                board, Board.RED, legalMoves);
        Move best = legalMoves.stream().max(Comparator.comparingDouble(
                move -> policy.getOrDefault(move, Float.NEGATIVE_INFINITY)))
                .orElseThrow();
        System.out.printf(Locale.ROOT,
                "{\"defender_score\":%d,\"attacker_score\":%d,"
                        + "\"best_move\":\"%s\",\"best_policy\":%.9f}%n",
                defenderScore, attackerScore, best,
                policy.getOrDefault(best, 0.0f));
    }
}
