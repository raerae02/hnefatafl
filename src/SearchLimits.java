/*
 * Limites immuables d'une recherche. Les parties reelles conservent la
 * garantie historique de terminer la profondeur 2, tandis que le self-play
 * respecte strictement son budget court et garde toujours un coup de secours.
 */
final class SearchLimits {
    final long timeBudgetMs;
    final int minDepth;
    final int maxDepth;
    final boolean guaranteeMinDepth;
    final boolean parallelRoot;

    private SearchLimits(long timeBudgetMs, int minDepth, int maxDepth,
                         boolean guaranteeMinDepth, boolean parallelRoot) {
        if (timeBudgetMs < 0 && timeBudgetMs != Long.MAX_VALUE) {
            throw new IllegalArgumentException("Le budget de recherche doit etre positif.");
        }
        if (minDepth < 1 || maxDepth < minDepth) {
            throw new IllegalArgumentException("Profondeurs de recherche invalides.");
        }
        this.timeBudgetMs = timeBudgetMs;
        this.minDepth = minDepth;
        this.maxDepth = maxDepth;
        this.guaranteeMinDepth = guaranteeMinDepth;
        this.parallelRoot = parallelRoot;
    }

    static SearchLimits live(long timeBudgetMs, boolean parallelRoot) {
        return new SearchLimits(timeBudgetMs, 2, 12, true, parallelRoot);
    }

    static SearchLimits training(long timeBudgetMs) {
        return new SearchLimits(timeBudgetMs, 1, 12, false, false);
    }

    static SearchLimits arena(long timeBudgetMs) {
        return new SearchLimits(timeBudgetMs, 1, 12, false, false);
    }

    static SearchLimits pondering(boolean parallelRoot) {
        return new SearchLimits(Long.MAX_VALUE, 2, 12, false, parallelRoot);
    }
}
