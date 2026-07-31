import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

final class ArenaJournal implements AutoCloseable {
    private final FileOutputStream fileOutput;
    private final BufferedWriter writer;
    private final Set<Integer> completedPairs = new HashSet<>();
    private int wins;
    private int losses;
    private int draws;
    private double points;

    ArenaJournal(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        if (Files.isRegularFile(path)) load(path);
        fileOutput = new FileOutputStream(path.toFile(), true);
        writer = new BufferedWriter(new OutputStreamWriter(
                fileOutput, StandardCharsets.UTF_8));
    }

    synchronized boolean isComplete(int pairIndex) {
        return completedPairs.contains(pairIndex);
    }

    synchronized void append(int pairIndex, GameScore first, GameScore second)
            throws IOException {
        if (!completedPairs.add(pairIndex)) return;
        record(first);
        record(second);
        writer.write(pairIndex + "," + first.code + "," + second.code);
        writer.newLine();
        writer.flush();
        fileOutput.getChannel().force(false);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(wins, losses, draws, points,
                wins + losses + draws, Set.copyOf(completedPairs));
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }

    private void load(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split(",");
                if (parts.length != 3) continue;
                try {
                    int pair = Integer.parseInt(parts[0]);
                    if (!completedPairs.add(pair)) continue;
                    record(GameScore.fromCode(Integer.parseInt(parts[1])));
                    record(GameScore.fromCode(Integer.parseInt(parts[2])));
                } catch (IllegalArgumentException ignored) {
                    // Une derniere ligne interrompue est simplement ignoree.
                }
            }
        }
    }

    private void record(GameScore score) {
        points += score.points;
        if (score == GameScore.WIN) wins++;
        else if (score == GameScore.LOSS) losses++;
        else draws++;
    }

    enum GameScore {
        LOSS(0, 0.0),
        DRAW(1, 0.5),
        WIN(2, 1.0);

        final int code;
        final double points;

        GameScore(int code, double points) {
            this.code = code;
            this.points = points;
        }

        static GameScore fromCode(int code) {
            return switch (code) {
                case 0 -> LOSS;
                case 1 -> DRAW;
                case 2 -> WIN;
                default -> throw new IllegalArgumentException("Invalid arena score.");
            };
        }
    }

    static final class Snapshot {
        final int wins;
        final int losses;
        final int draws;
        final double points;
        final int games;
        final Set<Integer> completedPairs;

        Snapshot(int wins, int losses, int draws, double points,
                 int games, Set<Integer> completedPairs) {
            this.wins = wins;
            this.losses = losses;
            this.draws = draws;
            this.points = points;
            this.games = games;
            this.completedPairs = completedPairs;
        }
    }
}
