import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/*
 * Format HTD1 :
 *   "HTD1", version u32
 *   [taille u32, crc32 u32, payload]...
 *
 * Chaque payload contient une partie complete. Une coupure ne peut donc
 * corrompre que la derniere frame, que recoverPartials tronque proprement.
 */
final class HtdDataset implements AutoCloseable {
    static final int VERSION = 1;
    private static final byte[] MAGIC = {'H', 'T', 'D', '1'};
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private static final AtomicInteger NEXT_FILE = new AtomicInteger();

    private final Path outputDirectory;
    private final int gamesPerShard;
    private FileOutputStream fileOutput;
    private OutputStream output;
    private Path partialPath;
    private int gamesInShard;
    private long totalSamples;

    HtdDataset(Path outputDirectory, int gamesPerShard) throws IOException {
        this.outputDirectory = outputDirectory;
        this.gamesPerShard = gamesPerShard;
        Files.createDirectories(outputDirectory);
        openShard();
    }

    synchronized void append(SelfPlayGame game) throws IOException {
        byte[] payload = encodeGame(game);
        CRC32 crc = new CRC32();
        crc.update(payload);
        writeInt(output, payload.length);
        writeInt(output, (int) crc.getValue());
        output.write(payload);
        output.flush();
        fileOutput.getChannel().force(false);
        gamesInShard++;
        totalSamples += game.samples.size();
        if (gamesInShard >= gamesPerShard) rotateShard();
    }

    synchronized long totalSamples() {
        return totalSamples;
    }

    @Override
    public synchronized void close() throws IOException {
        if (output == null) return;
        output.close();
        output = null;
        fileOutput = null;
        if (gamesInShard == 0) {
            Files.deleteIfExists(partialPath);
        } else {
            finishPartial(partialPath);
        }
    }

    static int recoverPartials(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        int recovered = 0;
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(directory, "*.partial")) {
            for (Path path : stream) {
                recoverPartial(path);
                finishPartial(path);
                recovered++;
            }
        }
        return recovered;
    }

    static DatasetStats inspect(Path path) throws IOException {
        int games = 0;
        long samples = 0;
        try (InputStream input = new BufferedInputStream(
                Files.newInputStream(path))) {
            verifyHeader(input);
            while (true) {
                int length;
                try {
                    length = readInt(input);
                } catch (EOFException e) {
                    break;
                }
                int expectedCrc = readInt(input);
                if (length < 13 || length > MAX_FRAME_BYTES) {
                    throw new IOException("Frame HTD1 invalide.");
                }
                byte[] payload = input.readNBytes(length);
                if (payload.length != length || crc32(payload) != expectedCrc) {
                    throw new IOException("Frame HTD1 tronquee ou corrompue.");
                }
                games++;
                samples += readInt(payload, 9);
            }
        }
        return new DatasetStats(games, samples);
    }

    private void openShard() throws IOException {
        String name = "selfplay-" + Instant.now().toEpochMilli()
                + "-" + ProcessHandle.current().pid()
                + "-" + NEXT_FILE.incrementAndGet() + ".partial";
        partialPath = outputDirectory.resolve(name);
        fileOutput = new FileOutputStream(partialPath.toFile(), false);
        output = new BufferedOutputStream(fileOutput);
        output.write(MAGIC);
        writeInt(output, VERSION);
        output.flush();
        gamesInShard = 0;
    }

    private void rotateShard() throws IOException {
        output.close();
        output = null;
        fileOutput = null;
        finishPartial(partialPath);
        openShard();
    }

    private static byte[] encodeGame(SelfPlayGame game) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(
                Math.max(256, game.samples.size() * 210));
        payload.write((byte) game.outcome);
        writeLong(payload, game.seed);
        writeInt(payload, game.samples.size());

        for (TrainingSample sample : game.samples) {
            if (sample.position.length != Board.BOARD_SIZE * Board.BOARD_SIZE) {
                throw new IOException("Position HTD1 de mauvaise taille.");
            }
            payload.write(sample.position);
            payload.write((byte) sample.sideToMove);
            writeShort(payload, sample.chosenMove.encode());
            writeShort(payload, sample.ply);
            int rankedCount = Math.min(8, sample.rankedMoves.size());
            payload.write((byte) rankedCount);
            for (int index = 0; index < rankedCount; index++) {
                ScoredMove ranked = sample.rankedMoves.get(index);
                writeShort(payload, ranked.move.encode());
                writeInt(payload, ranked.score);
            }
        }
        return payload.toByteArray();
    }

    private static void recoverPartial(Path path) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            if (file.length() < 8) {
                file.setLength(0);
                file.write(MAGIC);
                writeInt(file, VERSION);
                return;
            }

            byte[] magic = new byte[4];
            file.readFully(magic);
            for (int index = 0; index < MAGIC.length; index++) {
                if (magic[index] != MAGIC[index]) {
                    throw new IOException("Signature HTD1 invalide : " + path);
                }
            }
            if (readInt(file) != VERSION) {
                throw new IOException("Version HTD1 invalide : " + path);
            }

            long validEnd = file.getFilePointer();
            while (file.getFilePointer() + 8 <= file.length()) {
                int length = readInt(file);
                int expectedCrc = readInt(file);
                if (length < 13 || length > MAX_FRAME_BYTES
                        || file.getFilePointer() + length > file.length()) {
                    break;
                }
                byte[] payload = new byte[length];
                file.readFully(payload);
                if (crc32(payload) != expectedCrc) break;
                validEnd = file.getFilePointer();
            }
            file.setLength(validEnd);
        }
    }

    private static void finishPartial(Path partial) throws IOException {
        if (partial == null || !Files.exists(partial)) return;
        Path target = partial.resolveSibling(
                partial.getFileName().toString().replaceFirst(
                        "\\.partial$", ".htd"));
        try {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void verifyHeader(InputStream input) throws IOException {
        byte[] magic = input.readNBytes(4);
        if (magic.length != 4) throw new EOFException();
        for (int index = 0; index < MAGIC.length; index++) {
            if (magic[index] != MAGIC[index]) {
                throw new IOException("Signature HTD1 invalide.");
            }
        }
        if (readInt(input) != VERSION) {
            throw new IOException("Version HTD1 invalide.");
        }
    }

    private static int crc32(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        return (int) crc.getValue();
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static int readInt(InputStream input) throws IOException {
        int first = input.read();
        int second = input.read();
        int third = input.read();
        int fourth = input.read();
        if ((first | second | third | fourth) < 0) throw new EOFException();
        return first | (second << 8) | (third << 16) | (fourth << 24);
    }

    private static int readInt(RandomAccessFile input) throws IOException {
        return input.readUnsignedByte()
                | (input.readUnsignedByte() << 8)
                | (input.readUnsignedByte() << 16)
                | (input.readUnsignedByte() << 24);
    }

    private static void writeShort(OutputStream output, int value)
            throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(OutputStream output, int value)
            throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private static void writeInt(RandomAccessFile output, int value)
            throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private static void writeLong(OutputStream output, long value)
            throws IOException {
        for (int shift = 0; shift < Long.SIZE; shift += 8) {
            output.write((int) (value >>> shift) & 0xFF);
        }
    }

    static final class DatasetStats {
        final int games;
        final long samples;

        DatasetStats(int games, long samples) {
            this.games = games;
            this.samples = samples;
        }
    }
}
