import java.util.Objects;

/** Allocation-free helpers for the 169 squares of a 13x13 board. */
final class BitBoard169 {
    static final int SEGMENTS = 3;
    static final long LAST_SEGMENT_MASK = (1L << 41) - 1L;

    private BitBoard169() {}

    static int square(int row, int col) {
        return row * Board.BOARD_SIZE + col;
    }

    static int row(int square) {
        return square / Board.BOARD_SIZE;
    }

    static int col(int square) {
        return square % Board.BOARD_SIZE;
    }

    static boolean contains(long low, long middle, long high, int square) {
        long bit = 1L << (square & 63);
        return switch (square >>> 6) {
            case 0 -> (low & bit) != 0;
            case 1 -> (middle & bit) != 0;
            default -> (high & bit) != 0;
        };
    }

    static long toggleSegment(long segment, int square) {
        return segment ^ (1L << (square & 63));
    }

    static int count(long low, long middle, long high) {
        return Long.bitCount(low) + Long.bitCount(middle) + Long.bitCount(high);
    }

    static PositionBits bits(long low, long middle, long high) {
        return new PositionBits(low, middle, high & LAST_SEGMENT_MASK);
    }

    record PositionBits(long low, long middle, long high) {
        PositionBits {
            high &= LAST_SEGMENT_MASK;
        }

        boolean contains(int square) {
            return BitBoard169.contains(low, middle, high, square);
        }

        int count() {
            return BitBoard169.count(low, middle, high);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PositionBits bits)) return false;
            return low == bits.low && middle == bits.middle && high == bits.high;
        }

        @Override
        public int hashCode() {
            return Objects.hash(low, middle, high);
        }
    }
}
