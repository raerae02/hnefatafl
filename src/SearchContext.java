import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/*
 * Etat appartenant a une seule recherche iterative. Il remplace les anciennes
 * variables statiques de Board et peut etre partage sans danger par les taches
 * qui explorent les coups de la racine.
 */
final class SearchContext {
    private final int playerToHelp;
    private final long deadlineNanos;
    private final SearchLimits limits;
    private final PositionEvaluator evaluator;
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final TranspositionTable transpositionTable;
    private final int tableGeneration;
    private final ExecutorService rootExecutor;
    private final boolean parallelRoot;
    private final long startNanos = System.nanoTime();

    private final LongAdder visitedNodes = new LongAdder();
    private final LongAdder evaluatedLeaves = new LongAdder();
    private final LongAdder cutoffs = new LongAdder();
    private final LongAdder tableHits = new LongAdder();
    private final LongAdder tableCutoffs = new LongAdder();

    private volatile boolean ignoreDeadline;

    private SearchContext(int playerToHelp, SearchLimits limits,
                          PositionEvaluator evaluator,
                          TranspositionTable transpositionTable,
                          ExecutorService rootExecutor) {
        this.playerToHelp = playerToHelp;
        this.limits = limits;
        this.evaluator = evaluator == null ? HeuristicEvaluator.INSTANCE : evaluator;
        this.deadlineNanos = limits.timeBudgetMs == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : System.nanoTime() + limits.timeBudgetMs * 1_000_000L;
        this.transpositionTable = transpositionTable;
        this.tableGeneration = transpositionTable.beginSearch();
        this.rootExecutor = rootExecutor;
        this.parallelRoot = limits.parallelRoot && rootExecutor != null;
    }

    static SearchContext timed(int playerToHelp, long timeBudgetMs,
                               TranspositionTable transpositionTable,
                               ExecutorService rootExecutor, boolean parallelRoot) {
        return create(playerToHelp, SearchLimits.live(timeBudgetMs, parallelRoot),
                HeuristicEvaluator.INSTANCE, transpositionTable, rootExecutor);
    }

    static SearchContext create(int playerToHelp, SearchLimits limits,
                                PositionEvaluator evaluator,
                                TranspositionTable transpositionTable,
                                ExecutorService rootExecutor) {
        return new SearchContext(playerToHelp, limits, evaluator,
                transpositionTable, rootExecutor);
    }

    static SearchContext pondering(int playerToHelp,
                                   TranspositionTable transpositionTable,
                                   ExecutorService rootExecutor, boolean parallelRoot) {
        return create(playerToHelp, SearchLimits.pondering(parallelRoot),
                HeuristicEvaluator.INSTANCE, transpositionTable, rootExecutor);
    }

    void beginIteration(int depth) {
        ignoreDeadline = limits.guaranteeMinDepth && depth <= limits.minDepth;
    }

    void checkStopped() {
        if (stopRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new SearchTimeout();
        }

        if (!ignoreDeadline && deadlineNanos != Long.MAX_VALUE
                && System.nanoTime() > deadlineNanos) {
            throw new SearchTimeout();
        }
    }

    void requestStop() {
        stopRequested.set(true);
    }

    int playerToHelp() {
        return playerToHelp;
    }

    PositionEvaluator evaluator() {
        return evaluator;
    }

    int minDepth() {
        return limits.minDepth;
    }

    int maxDepth() {
        return limits.maxDepth;
    }

    TranspositionTable table() {
        return transpositionTable;
    }

    int tableGeneration() {
        return tableGeneration;
    }

    ExecutorService rootExecutor() {
        return rootExecutor;
    }

    boolean usesParallelRoot() {
        return parallelRoot;
    }

    void recordNode() {
        visitedNodes.increment();
    }

    void recordLeaf() {
        evaluatedLeaves.increment();
    }

    void recordCutoff() {
        cutoffs.increment();
    }

    void recordTableHit() {
        tableHits.increment();
    }

    void recordTableCutoff() {
        tableCutoffs.increment();
    }

    long visitedNodes() {
        return visitedNodes.sum();
    }

    long evaluatedLeaves() {
        return evaluatedLeaves.sum();
    }

    long cutoffs() {
        return cutoffs.sum();
    }

    long tableHits() {
        return tableHits.sum();
    }

    long tableCutoffs() {
        return tableCutoffs.sum();
    }

    long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
