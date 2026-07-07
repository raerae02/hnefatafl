import java.io.*;
import java.net.*;


class Client {
    private static final long TIME_LIMIT_MS = 2500;
    private static final GameSession session = new GameSession();

    public static void main(String[] args) {

        Socket MyClient;
        BufferedInputStream input;
        BufferedOutputStream output;
        Board gameBoard = null;
        CPUPlayer cpu = null;
        int player = Board.RED;

        try {
            MyClient = new Socket("localhost", 8888);

            input = new BufferedInputStream(MyClient.getInputStream());
            output = new BufferedOutputStream(MyClient.getOutputStream());

            while (1 == 1) {
                char cmd = 0;

                cmd = (char) input.read();
                System.out.println(cmd);

                // Debut de la partie en joueur blanc
                if (cmd == '1') {
                    String s = readServerPayload(input, 1024);
                    System.out.println(s);
                    int[][] board = parseBoard(s);

                    player = Board.RED;
                    gameBoard = new Board(board);
                    cpu = new CPUPlayer(player);
                    session.reset(gameBoard);

                    System.out.println("Nouvelle partie! Vous jouer blanc.");
                    sendCpuMove(gameBoard, cpu, output);
                }

                // Debut de la partie en joueur Noir
                if (cmd == '2') {
                    System.out.println("Nouvelle partie! Vous jouer noir, attendez le coup des blancs");
                    String s = readServerPayload(input, 1024);
                    System.out.println(s);
                    int[][] board = parseBoard(s);

                    player = Board.BLACK;
                    gameBoard = new Board(board);
                    cpu = new CPUPlayer(player);
                    session.reset(gameBoard);
                }

                // Le serveur demande le prochain coup
                // Le message contient aussi le dernier coup joue.
                if (cmd == '3') {
                    String s = readServerPayload(input, 16);
                    System.out.println("Dernier coup :" + s);

                    if (gameBoard != null && cpu != null) {
                        applyOpponentMove(gameBoard, s);
                        sendCpuMove(gameBoard, cpu, output);
                    }
                }

                // Le dernier coup est invalide
                if (cmd == '4') {
                    System.out.println("Coup invalide, calcul d'un nouveau coup : ");

                    if (gameBoard != null && cpu != null) {
                        Move rejectedMove = session.lastOwnMove();
                        session.revertLastOwnMove(gameBoard);
                        session.rememberRejectedMove(gameBoard, rejectedMove);
                        sendCpuMove(gameBoard, cpu, output);
                    }
                }

                // La partie est terminee
                if (cmd == '5') {
                    String s = readServerPayload(input, 16);
                    System.out.println("Partie Terminee. Le dernier coup joue est: " + s);
                    break;
                }
            }
        } catch (IOException e) {
            System.out.println(e);
        }
    }

    private static int[][] parseBoard(String payload) {
        int[][] board = new int[Board.BOARD_SIZE][Board.BOARD_SIZE];
        String[] boardValues = payload.split(" ");

        int x = 0;
        int y = 0;
        for (String boardValue : boardValues) {
            board[y][x] = Integer.parseInt(boardValue);
            x++;
            if (x == Board.BOARD_SIZE) {
                x = 0;
                y++;
            }
        }

        return board;
    }

    private static void applyOpponentMove(Board gameBoard, String moveText) {
        session.clearLastOwnMove();

        if (moveText.length() == 0 || moveText.equals("A0-A0") || moveText.equals("A0A0")) {
            return;
        }

        Move move = Move.parse(moveText);
        if (!gameBoard.isValidMove(move)) {
            System.out.println("Coup adverse invalide: " + moveText);
            return;
        }

        gameBoard.applyMove(move);
        session.recordPosition(gameBoard);

        if (session.isRepeatedPosition(gameBoard)) {
            System.out.println("Position repetee detectee apres coup adverse.");
        }
    }

    private static void sendCpuMove(Board gameBoard, CPUPlayer cpu, BufferedOutputStream output) throws IOException {
        Move move = cpu.findBestMove(gameBoard, TIME_LIMIT_MS, session.positionHistory(), session.getRejectedMoves(gameBoard));

        if (move == null) {
            System.out.println("Aucun coup legal.");
            return;
        }

        MoveUndo undo = gameBoard.makeMove(move);
        session.rememberOwnMove(move, undo);
        session.recordPosition(gameBoard);

        if (session.isRepeatedPosition(gameBoard)) {
            System.out.println("Position repetee detectee apres notre coup.");
        }

        String moveText = move.toString();
        output.write(moveText.getBytes(), 0, moveText.length());
        output.flush();

        System.out.println("Coup joue : " + moveText + " (" + cpu.getNumExploredNodes() + " noeuds)");
    }

    private static String readServerPayload(BufferedInputStream input, int maxBytes) throws IOException {
        byte[] buffer = new byte[maxBytes];

        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int size = input.available();
        if (size <= 0) return "";
        if (size > maxBytes) size = maxBytes;

        int read = input.read(buffer, 0, size);
        if (read <= 0) return "";

        return new String(buffer, 0, read).trim();
    }
}
