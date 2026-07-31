import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class SearchResult {
    final Move bestMove;
    final int bestScore;
    final int completedDepth;
    final long visitedNodes;
    final long evaluatedLeaves;
    final long cutoffs;
    final long tableHits;
    final long tableCutoffs;
    final long elapsedMillis;
    final List<ScoredMove> rankedMoves;

    SearchResult(Move bestMove, int bestScore, int completedDepth,
                 SearchContext context) {
        this(bestMove, bestScore, completedDepth, context, null, true);
    }

    SearchResult(Move bestMove, int bestScore, int completedDepth,
                 SearchContext context, Map<Move, Integer> rootScores,
                 boolean maximizingRoot) {
        this.bestMove = bestMove;
        this.bestScore = bestScore;
        this.completedDepth = completedDepth;
        this.visitedNodes = context.visitedNodes();
        this.evaluatedLeaves = context.evaluatedLeaves();
        this.cutoffs = context.cutoffs();
        this.tableHits = context.tableHits();
        this.tableCutoffs = context.tableCutoffs();
        this.elapsedMillis = context.elapsedMillis();
        this.rankedMoves = rankMoves(
                bestMove, bestScore, rootScores, maximizingRoot);
    }

    private static List<ScoredMove> rankMoves(
            Move bestMove, int bestScore, Map<Move, Integer> rootScores,
            boolean maximizingRoot) {
        if (rootScores == null || rootScores.isEmpty()) {
            return bestMove == null
                    ? List.of()
                    : List.of(new ScoredMove(bestMove, bestScore));
        }

        List<ScoredMove> ranked = new ArrayList<>(rootScores.size());
        for (Map.Entry<Move, Integer> entry : rootScores.entrySet()) {
            ranked.add(new ScoredMove(entry.getKey(), entry.getValue()));
        }
        Comparator<ScoredMove> comparator =
                Comparator.comparingInt(scored -> scored.score);
        if (maximizingRoot) comparator = comparator.reversed();
        ranked.sort(comparator);
        if (ranked.size() > 8) {
            return List.copyOf(ranked.subList(0, 8));
        }
        return List.copyOf(ranked);
    }
}
