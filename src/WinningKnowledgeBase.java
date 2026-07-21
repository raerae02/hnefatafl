import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Exact-position, versioned storage for proven winning lines. */
public final class WinningKnowledgeBase {
    private static final int MAGIC = 0x484E4546; // HNEF
    private static final int VERSION = 2;
    private static final int RULE_VERSION = 1;
    private static final int MAX_ENTRIES = 1_000_000;
    private static final int MAX_PV = 400;
    private final Map<PositionKey, KnowledgeEntry> entries = new ConcurrentHashMap<>();

    KnowledgeEntry get(PositionKey key) { return entries.get(key); }
    public int size() { return entries.size(); }

    void putProven(PositionKey key, int winner, int bestMove, int depth, int distance, List<Move> pv) {
        KnowledgeEntry previous = entries.get(key);
        if (previous != null && previous.type() == KnowledgeType.PROVEN && previous.searchDepth() > depth) return;
        List<Integer> packed = pv.stream().limit(MAX_PV).map(PackedMove::pack).toList();
        entries.put(key, new KnowledgeEntry(KnowledgeType.PROVEN, winner, bestMove, depth, distance, packed));
    }

    public void save(Path path) throws IOException {
        if (path.toAbsolutePath().getParent() != null) Files.createDirectories(path.toAbsolutePath().getParent());
        List<Map.Entry<PositionKey, KnowledgeEntry>> stored = List.copyOf(entries.entrySet());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(RULE_VERSION);
            out.writeInt(Board.BOARD_SIZE); out.writeInt(stored.size());
            for (Map.Entry<PositionKey, KnowledgeEntry> item : stored) {
                writeKey(out, item.getKey());
                KnowledgeEntry e = item.getValue();
                out.writeByte(e.type().ordinal()); out.writeInt(e.winner()); out.writeInt(e.bestMove());
                out.writeInt(e.searchDepth()); out.writeInt(e.distanceToWin());
                out.writeInt(e.principalVariation().size());
                for (int move : e.principalVariation()) out.writeInt(move);
            }
        }
    }

    public static WinningKnowledgeBase load(Path path) throws IOException {
        WinningKnowledgeBase database = new WinningKnowledgeBase();
        if (!Files.exists(path)) return database;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION || in.readInt() != RULE_VERSION
                    || in.readInt() != Board.BOARD_SIZE) throw new IOException("Incompatible knowledge database");
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid knowledge entry count");
            for (int i = 0; i < count; i++) {
                PositionKey key = readKey(in);
                int type = in.readUnsignedByte();
                if (type >= KnowledgeType.values().length) throw new IOException("Invalid knowledge type");
                int winner = in.readInt(), bestMove = in.readInt(), depth = in.readInt(), distance = in.readInt();
                int pvSize = in.readInt();
                if (pvSize < 0 || pvSize > MAX_PV) throw new IOException("Invalid principal variation length");
                List<Integer> pv = new ArrayList<>(pvSize);
                for (int p = 0; p < pvSize; p++) pv.add(in.readInt());
                database.entries.put(key, new KnowledgeEntry(KnowledgeType.PROVEN, winner, bestMove,
                        depth, distance, pv));
            }
            if (in.read() != -1) throw new IOException("Trailing data in knowledge database");
        } catch (EOFException e) {
            throw new IOException("Truncated knowledge database", e);
        }
        return database;
    }

    private static void writeKey(DataOutputStream out, PositionKey k) throws IOException {
        out.writeLong(k.redLow()); out.writeLong(k.redMiddle()); out.writeLong(k.redHigh());
        out.writeLong(k.blackLow()); out.writeLong(k.blackMiddle()); out.writeLong(k.blackHigh());
        out.writeLong(k.kingLow()); out.writeLong(k.kingMiddle()); out.writeLong(k.kingHigh());
        out.writeInt(k.sideToMove());
    }

    private static PositionKey readKey(DataInputStream in) throws IOException {
        return new PositionKey(in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readLong(),
                in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readInt());
    }
}
