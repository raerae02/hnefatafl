import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/*
 * Lecteur/inference du format HNN1 exporte par training/train_model.py.
 *
 * Le transformeur additionne des embeddings absolus et relatifs au roi. Cette
 * representation reste suffisamment petite pour etre recalculee aux feuilles
 * alpha-beta sans runtime natif. Les tampons sont propres a chaque thread.
 */
final class NnueEvaluator implements PositionEvaluator {
    private static final byte[] MAGIC = {'H', 'N', 'N', '1'};
    private static final int VERSION = 1;
    private static final int PIECE_TYPES = 3;
    private static final int RELATIVE_SQUARES = 25 * 25;
    private static final int VALUE_SCALE = 80_000;

    private final int hidden;
    private final long generation;
    private final String checksum;
    private final float[] hiddenBias;
    private final float[] absoluteEmbeddings;
    private final float[] relativeEmbeddings;
    private final float[] sideEmbeddings;
    private final float[] countWeights;
    private final float[] valueWeights;
    private final float valueBias;
    private final float[] originWeights;
    private final float[] originBias;
    private final float[] destinationWeights;
    private final float[] destinationBias;
    private final ThreadLocal<Scratch> scratch;

    private NnueEvaluator(
            int hidden, long generation, String checksum,
            float[] hiddenBias, float[] absoluteEmbeddings,
            float[] relativeEmbeddings, float[] sideEmbeddings,
            float[] countWeights, float[] valueWeights, float valueBias,
            float[] originWeights, float[] originBias,
            float[] destinationWeights, float[] destinationBias) {
        this.hidden = hidden;
        this.generation = generation;
        this.checksum = checksum;
        this.hiddenBias = hiddenBias;
        this.absoluteEmbeddings = absoluteEmbeddings;
        this.relativeEmbeddings = relativeEmbeddings;
        this.sideEmbeddings = sideEmbeddings;
        this.countWeights = countWeights;
        this.valueWeights = valueWeights;
        this.valueBias = valueBias;
        this.originWeights = originWeights;
        this.originBias = originBias;
        this.destinationWeights = destinationWeights;
        this.destinationBias = destinationBias;
        this.scratch = ThreadLocal.withInitial(() -> new Scratch(hidden));
    }

    static NnueEvaluator load(Path path) throws IOException {
        byte[] file = Files.readAllBytes(path);
        if (file.length < 60) {
            throw new IOException("Modele HNN1 tronque : " + path);
        }

        ByteBuffer header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        for (byte expected : MAGIC) {
            if (header.get() != expected) {
                throw new IOException("Signature HNN1 invalide : " + path);
            }
        }

        int version = header.getInt();
        int boardSize = header.getInt();
        int hidden = header.getInt();
        long generation = header.getLong();
        int payloadLength = header.getInt();
        byte[] expectedChecksum = new byte[32];
        header.get(expectedChecksum);

        if (version != VERSION || boardSize != Board.BOARD_SIZE || hidden <= 0
                || hidden > 1024 || payloadLength != file.length - header.position()) {
            throw new IOException("En-tete HNN1 incompatible : " + path);
        }

        byte[] actualChecksum = sha256(
                file, header.position(), payloadLength);
        if (!MessageDigest.isEqual(expectedChecksum, actualChecksum)) {
            throw new IOException("Checksum HNN1 invalide : " + path);
        }

        ByteBuffer payload = ByteBuffer.wrap(
                file, header.position(), payloadLength).slice()
                .order(ByteOrder.LITTLE_ENDIAN);
        float[] hiddenBias = readFloats(payload, hidden);
        float[] absolute = readFloats(
                payload, PIECE_TYPES * Board.BOARD_SIZE * Board.BOARD_SIZE * hidden);
        float[] relative = readFloats(
                payload, PIECE_TYPES * RELATIVE_SQUARES * hidden);
        float[] side = readFloats(payload, 2 * hidden);
        float[] counts = readFloats(payload, 2 * hidden);
        float[] value = readFloats(payload, hidden);
        float valueBias = payload.getFloat();
        int squareCount = Board.BOARD_SIZE * Board.BOARD_SIZE;
        float[] originWeights = readFloats(payload, squareCount * hidden);
        float[] originBias = readFloats(payload, squareCount);
        float[] destinationWeights = readFloats(payload, squareCount * hidden);
        float[] destinationBias = readFloats(payload, squareCount);

        if (payload.hasRemaining()) {
            throw new IOException("Donnees supplementaires dans le modele HNN1.");
        }

        return new NnueEvaluator(
                hidden, generation, HexFormat.of().formatHex(actualChecksum),
                hiddenBias, absolute, relative, side, counts, value, valueBias,
                originWeights, originBias, destinationWeights, destinationBias);
    }

    @Override
    public int evaluate(Board board, int perspective, int sideToMove) {
        float[] activation = buildActivation(board, sideToMove);
        float value = valueBias;
        for (int index = 0; index < hidden; index++) {
            value += activation[index] * valueWeights[index];
        }
        int defenderScore = Math.round(
                (float) Math.tanh(value) * VALUE_SCALE);
        return perspective == Board.BLACK ? defenderScore : -defenderScore;
    }

    @Override
    public Map<Move, Float> rootPolicyScores(
            Board board, int sideToMove, List<Move> legalMoves) {
        if (legalMoves.isEmpty()) return Map.of();

        float[] activation = buildActivation(board, sideToMove);
        Scratch local = scratch.get();
        computePolicyLogits(
                activation, originWeights, originBias, local.originLogits);
        computePolicyLogits(
                activation, destinationWeights, destinationBias,
                local.destinationLogits);

        Map<Move, Float> result = new HashMap<>(legalMoves.size() * 2);
        for (Move move : legalMoves) {
            int from = move.fromRow * Board.BOARD_SIZE + move.fromCol;
            int to = move.toRow * Board.BOARD_SIZE + move.toCol;
            result.put(move, local.originLogits[from] + local.destinationLogits[to]);
        }
        return result;
    }

    @Override
    public String description() {
        return "HNN1 generation " + generation
                + " checksum " + checksum.substring(0, 12);
    }

    long generation() {
        return generation;
    }

    String checksum() {
        return checksum;
    }

    private float[] buildActivation(Board board, int sideToMove) {
        Scratch local = scratch.get();
        float[] activation = local.activation;
        System.arraycopy(hiddenBias, 0, activation, 0, hidden);

        int kingSquare = findKing(board);
        int kingRow = kingSquare / Board.BOARD_SIZE;
        int kingCol = kingSquare % Board.BOARD_SIZE;
        int attackers = 0;
        int defenders = 0;

        for (int row = 0; row < Board.BOARD_SIZE; row++) {
            for (int col = 0; col < Board.BOARD_SIZE; col++) {
                int piece = board.grid[row][col];
                int pieceIndex = pieceIndex(piece);
                if (pieceIndex < 0) continue;
                if (piece == Board.RED) attackers++;
                else if (piece == Board.BLACK) defenders++;

                int square = row * Board.BOARD_SIZE + col;
                addEmbedding(activation, absoluteEmbeddings,
                        (pieceIndex * Board.BOARD_SIZE * Board.BOARD_SIZE
                                + square) * hidden);

                int relativeRow = row - kingRow + 12;
                int relativeCol = col - kingCol + 12;
                int relativeSquare = relativeRow * 25 + relativeCol;
                addEmbedding(activation, relativeEmbeddings,
                        (pieceIndex * RELATIVE_SQUARES + relativeSquare) * hidden);
            }
        }

        int sideIndex = sideToMove == Board.BLACK ? 0 : 1;
        addEmbedding(activation, sideEmbeddings, sideIndex * hidden);
        float normalizedAttackers = attackers / 24.0f;
        float normalizedDefenders = defenders / 12.0f;
        for (int index = 0; index < hidden; index++) {
            activation[index] += countWeights[index] * normalizedAttackers
                    + countWeights[hidden + index] * normalizedDefenders;
            activation[index] = Math.max(
                    0.0f, Math.min(1.0f, activation[index]));
        }
        return activation;
    }

    private void addEmbedding(float[] target, float[] source, int offset) {
        for (int index = 0; index < hidden; index++) {
            target[index] += source[offset + index];
        }
    }

    private void computePolicyLogits(
            float[] activation, float[] weights, float[] bias, float[] output) {
        for (int square = 0; square < output.length; square++) {
            float logit = bias[square];
            int offset = square * hidden;
            for (int index = 0; index < hidden; index++) {
                logit += activation[index] * weights[offset + index];
            }
            output[square] = logit;
        }
    }

    private static int findKing(Board board) {
        for (int row = 0; row < Board.BOARD_SIZE; row++) {
            for (int col = 0; col < Board.BOARD_SIZE; col++) {
                if (board.grid[row][col] == Board.KING) {
                    return row * Board.BOARD_SIZE + col;
                }
            }
        }
        return 6 * Board.BOARD_SIZE + 6;
    }

    private static int pieceIndex(int piece) {
        if (piece == Board.RED) return 0;
        if (piece == Board.BLACK) return 1;
        if (piece == Board.KING) return 2;
        return -1;
    }

    private static float[] readFloats(ByteBuffer buffer, int count)
            throws IOException {
        if (buffer.remaining() < count * Float.BYTES) {
            throw new IOException("Modele HNN1 tronque.");
        }
        float[] values = new float[count];
        for (int index = 0; index < count; index++) {
            values[index] = buffer.getFloat();
        }
        return values;
    }

    private static byte[] sha256(byte[] bytes, int offset, int length)
            throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, offset, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 indisponible.", e);
        }
    }

    private static final class Scratch {
        final float[] activation;
        final float[] originLogits =
                new float[Board.BOARD_SIZE * Board.BOARD_SIZE];
        final float[] destinationLogits =
                new float[Board.BOARD_SIZE * Board.BOARD_SIZE];

        Scratch(int hidden) {
            activation = new float[hidden];
        }
    }
}
