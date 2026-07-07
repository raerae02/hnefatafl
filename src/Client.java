import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

class Client {
    private static final int SEARCH_DEPTH = 2;
    private static final int MAX_REJECTED_MOVES = 8;

    public static void main(String[] args) {
        Board board = null;
        Board boardBeforeLastMove = null;
        int myPlayer = Board.RED;
        Move lastSentMove = null;
        List<String> rejectedMoves = new ArrayList<>();

        try {
            Socket myClient = new Socket("localhost", 8888);
            BufferedInputStream input = new BufferedInputStream(myClient.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(myClient.getOutputStream());

            while (true) {
                char cmd = (char)input.read();
                System.out.println(cmd);

                if (cmd == '1') {
                    myPlayer = Board.RED;
                    board = readInitialBoard(input);
                    System.out.println("Nouvelle partie comme joueur blanc.");

                    Move move = chooseMove(board, myPlayer, rejectedMoves);
                    boardBeforeLastMove = board.copy();
                    lastSentMove = move;
                    rejectedMoves.clear();
                    sendMove(output, move);
                    board.applyMove(move);
                }

                if (cmd == '2') {
                    myPlayer = Board.BLACK;
                    board = readInitialBoard(input);
                    System.out.println("Nouvelle partie comme joueur noir, attente du premier coup adverse.");
                }

                if (cmd == '3') {
                    String lastMove = readServerText(input, 16).trim();
                    System.out.println("Dernier coup : " + lastMove);

                    if (board != null && !lastMove.isEmpty()) {
                        board.applyMove(Move.parse(lastMove));
                    }

                    Move move = chooseMove(board, myPlayer, rejectedMoves);
                    boardBeforeLastMove = board.copy();
                    lastSentMove = move;
                    rejectedMoves.clear();
                    sendMove(output, move);
                    board.applyMove(move);
                }

                if (cmd == '4') {
                    System.out.println("Coup invalide.");

                    if (rejectedMoves.size() >= MAX_REJECTED_MOVES) {
                        throw new IOException("Trop de coups invalides consecutifs. Verification du camp ou du format requise.");
                    }

                    if (boardBeforeLastMove != null) {
                        board = boardBeforeLastMove.copy();
                    }
                    if (lastSentMove != null) {
                        rejectedMoves.add(lastSentMove.toString());
                    }

                    Move move = chooseMove(board, myPlayer, rejectedMoves);
                    boardBeforeLastMove = board.copy();
                    lastSentMove = move;
                    sendMove(output, move);
                    board.applyMove(move);
                }

                if (cmd == '5') {
                    String lastMove = readServerText(input, 16).trim();
                    System.out.println("Partie terminee. Dernier coup joue : " + lastMove);
                    break;
                }
            }

            myClient.close();
        } catch (IOException e) {
            System.out.println(e);
        }
    }

    private static Board readInitialBoard(BufferedInputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        long startTime = System.currentTimeMillis();

        while (countBoardValues(builder.toString()) < 169 && System.currentTimeMillis() - startTime < 2000) {
            String chunk = readServerText(input, 1024);
            if (!chunk.isEmpty()) {
                if (builder.length() > 0) builder.append(' ');
                builder.append(chunk);
            }
        }

        String boardText = builder.toString().trim();
        System.out.println(boardText);
        if (countBoardValues(boardText) < 169) {
            throw new IOException("Plateau initial incomplet recu du serveur.");
        }

        String[] boardValues = boardText.split("\\s+");
        int[][] grid = new int[13][13];

        int x = 0;
        int y = 0;
        for (int i = 0; i < boardValues.length && i < 169; i++) {
            grid[x][y] = Integer.parseInt(boardValues[i]);
            x++;
            if (x == 13) {
                x = 0;
                y++;
            }
        }

        return new Board(grid);
    }

    private static int countBoardValues(String boardText) {
        String trimmedText = boardText.trim();
        if (trimmedText.isEmpty()) return 0;
        return trimmedText.split("\\s+").length;
    }

    private static String readServerText(BufferedInputStream input, int bufferSize) throws IOException {
        byte[] buffer = new byte[bufferSize];
        int size = waitForAvailable(input);
        if (size <= 0) return "";
        int readSize = Math.min(size, buffer.length);
        input.read(buffer, 0, readSize);
        return new String(buffer, 0, readSize).trim();
    }

    private static int waitForAvailable(BufferedInputStream input) throws IOException {
        long startTime = System.currentTimeMillis();

        while (input.available() == 0 && System.currentTimeMillis() - startTime < 1000) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }

        return input.available();
    }

    private static Move chooseMove(Board board, int player, List<String> rejectedMoves) {
        Move bestMove = board.getBestMove(player, SEARCH_DEPTH);
        if (bestMove != null && !rejectedMoves.contains(bestMove.toString())) {
            return bestMove;
        }

        for (Move move : board.getLegalMoves(player)) {
            if (!rejectedMoves.contains(move.toString())) {
                return move;
            }
        }

        throw new IllegalStateException("Aucun coup legal disponible.");
    }

    private static void sendMove(BufferedOutputStream output, Move move) throws IOException {
        String moveText = move.toString();
        System.out.println("Coup joue : " + moveText);
        output.write(moveText.getBytes(), 0, moveText.length());
        output.flush();
    }
}
