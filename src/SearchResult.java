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

    SearchResult(Move bestMove, int bestScore, int completedDepth,
                 SearchContext context) {
        this.bestMove = bestMove;
        this.bestScore = bestScore;
        this.completedDepth = completedDepth;
        this.visitedNodes = context.visitedNodes();
        this.evaluatedLeaves = context.evaluatedLeaves();
        this.cutoffs = context.cutoffs();
        this.tableHits = context.tableHits();
        this.tableCutoffs = context.tableCutoffs();
        this.elapsedMillis = context.elapsedMillis();
    }
}
