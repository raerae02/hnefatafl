import java.util.List;

public record SearchResult(
        Move bestMove,
        int score,
        int completedDepth,
        long exploredNodes,
        long elapsedMillis,
        int mateDistance,
        List<Move> principalVariation) {

    public SearchResult {
        principalVariation = List.copyOf(principalVariation);
    }

    public boolean isForcedWin() {
        return score >= CPUPlayer.MATE_THRESHOLD;
    }
}
