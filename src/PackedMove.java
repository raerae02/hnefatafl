final class PackedMove {
    static final int NONE = -1;

    private PackedMove() {}

    static int pack(int fromSquare, int toSquare) {
        return fromSquare | (toSquare << 8);
    }

    static int pack(Move move) {
        return pack(BitBoard169.square(move.fromRow, move.fromCol),
                BitBoard169.square(move.toRow, move.toCol));
    }

    static int from(int packed) {
        return packed & 0xff;
    }

    static int to(int packed) {
        return (packed >>> 8) & 0xff;
    }

    static Move unpack(int packed) {
        int from = from(packed);
        int to = to(packed);
        return new Move(BitBoard169.row(from), BitBoard169.col(from),
                BitBoard169.row(to), BitBoard169.col(to));
    }
}
