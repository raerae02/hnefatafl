import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/*
 * Cache borne des positions deja recherchees.
 *
 * La table est indexee directement avec le hash Zobrist. Une collision peut
 * remplacer une ancienne position, mais la cle complete est toujours verifiee
 * avant d'utiliser une entree. Les entrees sont immuables, ce qui permet aux
 * recherches paralleles de partager la table sans verrou global.
 */
final class TranspositionTable {
    static final int DEFAULT_SIZE_POWER = 20; // 2^20, soit environ un million d'entrees

    enum Bound {
        EXACT,
        LOWER,
        UPPER
    }

    static final class Entry {
        final long key;
        final int depth;
        final int score;
        final Bound bound;
        final int encodedBestMove;
        final int generation;

        Entry(long key, int depth, int score, Bound bound,
              int encodedBestMove, int generation) {
            this.key = key;
            this.depth = depth;
            this.score = score;
            this.bound = bound;
            this.encodedBestMove = encodedBestMove;
            this.generation = generation;
        }

        Move bestMove() {
            return decodeMove(encodedBestMove);
        }
    }

    private final AtomicReferenceArray<Entry> entries;
    private final int indexMask;
    private final AtomicInteger nextGeneration = new AtomicInteger();

    TranspositionTable() {
        this(DEFAULT_SIZE_POWER);
    }

    TranspositionTable(int sizePower) {
        if (sizePower < 10 || sizePower > 26) {
            throw new IllegalArgumentException("La puissance de la table doit etre entre 10 et 26.");
        }

        int size = 1 << sizePower;
        entries = new AtomicReferenceArray<>(size);
        indexMask = size - 1;
    }

    int beginSearch() {
        return nextGeneration.incrementAndGet();
    }

    Entry find(long key) {
        Entry entry = entries.get(indexFor(key));
        return entry != null && entry.key == key ? entry : null;
    }

    void store(long key, int depth, int score, Bound bound,
               Move bestMove, int generation) {
        int index = indexFor(key);
        Entry candidate = new Entry(key, depth, score, bound,
                encodeMove(bestMove), generation);

        while (true) {
            Entry current = entries.get(index);
            if (!shouldReplace(current, candidate)) return;
            if (entries.compareAndSet(index, current, candidate)) return;
        }
    }

    void clear() {
        for (int index = 0; index < entries.length(); index++) {
            entries.set(index, null);
        }
    }

    private boolean shouldReplace(Entry current, Entry candidate) {
        if (current == null) return true;

        if (current.key == candidate.key) {
            if (candidate.depth > current.depth) return true;
            if (candidate.depth < current.depth) return false;
            return candidate.bound == Bound.EXACT || current.bound != Bound.EXACT;
        }

        int age = candidate.generation - current.generation;
        return age > 1 || candidate.depth >= current.depth;
    }

    private int indexFor(long key) {
        long mixed = key ^ (key >>> 33);
        mixed *= 0xff51afd7ed558ccdl;
        mixed ^= mixed >>> 33;
        return ((int) mixed) & indexMask;
    }

    private static int encodeMove(Move move) {
        if (move == null) return 0;

        int packed = move.fromRow
                | (move.fromCol << 4)
                | (move.toRow << 8)
                | (move.toCol << 12);
        return packed + 1;
    }

    private static Move decodeMove(int encodedMove) {
        if (encodedMove == 0) return null;

        int packed = encodedMove - 1;
        int fromRow = packed & 0xF;
        int fromCol = (packed >>> 4) & 0xF;
        int toRow = (packed >>> 8) & 0xF;
        int toCol = (packed >>> 12) & 0xF;
        return new Move(fromRow, fromCol, toRow, toCol);
    }
}
