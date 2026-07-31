import java.util.Collections;
import java.util.List;
import java.util.Map;

/*
 * Evaluation non terminale branchee dans alpha-beta. Les victoires et defaites
 * restent toujours traitees par Board avec leurs scores exacts.
 */
interface PositionEvaluator {
    int evaluate(Board board, int perspective, int sideToMove);

    default Map<Move, Float> rootPolicyScores(
            Board board, int sideToMove, List<Move> legalMoves) {
        return Collections.emptyMap();
    }

    default String description() {
        return "heuristique";
    }
}
