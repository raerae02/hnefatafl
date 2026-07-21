import java.util.List;

record KnowledgeEntry(
        KnowledgeType type,
        int winner,
        int bestMove,
        int searchDepth,
        int distanceToWin,
        List<Integer> principalVariation) {

    KnowledgeEntry {
        principalVariation = List.copyOf(principalVariation);
    }
}
