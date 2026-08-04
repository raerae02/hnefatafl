import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class Client {
    private static final long TIME_BUDGET_MS = 4400;   // marge sur les 5 s du serveur
                                                       // (pire depassement mesure ~70 ms apres l'echeance)
    private static final int MAX_REJECTED_MOVES = 8;

    public static void main(String[] args) {
        Board board = null;
        Board boardBeforeLastMove = null;
        int myPlayer = Board.RED;
        Move lastSentMove = null;
        List<String> rejectedMoves = new ArrayList<>();
        Map<String, Integer> positionHistory = new HashMap<>();
        List<String> movesLog = new ArrayList<>();

        try {
            Socket myClient = connectWithRetry("127.0.0.1", 8888, 120);
            BufferedInputStream input = new BufferedInputStream(myClient.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(myClient.getOutputStream());

            while (true) {
                char cmd = (char)input.read();
                System.out.println(cmd);

                if (cmd == '1') {
                    myPlayer = Board.RED;
                    board = readInitialBoard(input);
                    positionHistory.clear();
                    movesLog.clear();
                    System.out.println("Nouvelle partie comme joueur rouge.");

                    Move move = chooseMove(board, myPlayer, rejectedMoves, positionHistory, movesLog);
                    boardBeforeLastMove = board.copy();
                    lastSentMove = move;
                    rejectedMoves.clear();
                    sendMove(output, move);
                    movesLog.add(colorName(myPlayer) + " " + move);
                    board.applyMove(move);
                    recordPosition(board, positionHistory);
                    board.startPondering(opponentOf(myPlayer));
                }

                if (cmd == '2') {
                    myPlayer = Board.BLACK;
                    board = readInitialBoard(input);
                    lastSentMove = null;
                    positionHistory.clear();
                    movesLog.clear();
                    System.out.println("Nouvelle partie comme joueur noir, attente du premier coup adverse.");
                }

                if (cmd == '3') {
                    Board.stopPondering();
                    String lastMove = readServerText(input, 16).trim();
                    System.out.println("Dernier coup : " + lastMove);

                    Move opponentMove = Move.tryParse(lastMove);

                    // Coup bidon (ex. "A0-A0") alors qu'on a deja envoye notre coup :
                    // le serveur nous invite juste a jouer, mais c'est deja fait.
                    if (opponentMove == null && lastSentMove != null) {
                        continue;
                    }

                    if (board != null && opponentMove != null) {
                        // l'enonce demande de verifier la validite du coup adverse
                        if (!board.isValidMove(opponentMove)) {
                            System.out.println("ATTENTION : coup adverse invalide, ignore : " + lastMove);
                        } else {
                            board.applyMove(opponentMove);
                            recordPosition(board, positionHistory);
                            movesLog.add(colorName(opponentOf(myPlayer)) + " " + opponentMove);
                        }
                    }

                    Move move = chooseMove(board, myPlayer, rejectedMoves, positionHistory, movesLog);
                    boardBeforeLastMove = board.copy();
                    lastSentMove = move;
                    rejectedMoves.clear();
                    sendMove(output, move);
                    movesLog.add(colorName(myPlayer) + " " + move);
                    board.applyMove(move);
                    recordPosition(board, positionHistory);
                    board.startPondering(opponentOf(myPlayer));
                }

                if (cmd == '4') {
                    Board.stopPondering();
                    System.out.println("Coup invalide.");

                    if (rejectedMoves.size() >= MAX_REJECTED_MOVES) {
                        throw new IOException("Trop de coups invalides consecutifs. Verification du camp ou du format requise.");
                    }

                    if (boardBeforeLastMove != null) {
                        board = boardBeforeLastMove.copy();
                    }
                    if (lastSentMove != null) {
                        rejectedMoves.add(lastSentMove.toString());
                        // le coup refuse avait deja ete note, on le retire
                        if (!movesLog.isEmpty()) {
                            movesLog.remove(movesLog.size() - 1);
                        }
                    }

                    Move move = chooseMove(board, myPlayer, rejectedMoves, positionHistory, movesLog);
                    boardBeforeLastMove = board.copy();
                    lastSentMove = move;
                    sendMove(output, move);
                    movesLog.add(colorName(myPlayer) + " " + move);
                    board.applyMove(move);
                    recordPosition(board, positionHistory);
                }

                if (cmd == '5') {
                    Board.stopPondering();
                    String lastMove = readServerText(input, 16).trim();
                    System.out.println("Partie terminee. Dernier coup joue : " + lastMove);

                    // le gagnant recoit son propre dernier coup
                    boolean weWon = lastSentMove != null && lastMove.replace(" ", "").equals(lastSentMove.toString());
                    System.out.println(weWon ? "===> VICTOIRE !" : "===> DEFAITE (ou nul)");

                    // en cas de defaite, le coup gagnant adverse n'est pas passe par '3'
                    if (!weWon) {
                        Move finalMove = Move.tryParse(lastMove);
                        if (finalMove != null && (movesLog.isEmpty()
                                || !movesLog.get(movesLog.size() - 1).endsWith(finalMove.toString()))) {
                            movesLog.add(colorName(opponentOf(myPlayer)) + " " + finalMove);
                        }
                    }
                    saveMovesLog(movesLog, myPlayer, weWon);
                    break;
                }
            }

            myClient.close();
        } catch (IOException e) {
            System.out.println(e);
        }
    }

    // Reessaie la connexion tant que le serveur n'a pas demarre la partie.
    private static Socket connectWithRetry(String host, int port, int maxSeconds) throws IOException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (true) {
            try {
                return new Socket(host, port);
            } catch (IOException e) {
                if (System.currentTimeMillis() > deadline) throw e;
                System.out.println("Serveur pas encore pret, nouvelle tentative dans 1 s...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
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

    private static int opponentOf(int player) {
        return player == Board.RED ? Board.BLACK : Board.RED;
    }

    private static String colorName(int player) {
        return player == Board.RED ? "ROUGE" : "NOIR";
    }

    // Ecrit la liste des coups dans parties\partie_DATE_HEURE.txt pour analyse.
    private static void saveMovesLog(List<String> movesLog, int myPlayer, boolean weWon) {
        try {
            File dir = new File("parties");
            dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File file = new File(dir, "partie_" + stamp + ".txt");
            try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
                out.println("# Partie du " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                out.println("# Mon camp : " + colorName(myPlayer));
                out.println("# Resultat : " + (weWon ? "VICTOIRE" : "DEFAITE (ou nul)"));
                int numero = 1;
                for (String coup : movesLog) {
                    out.println(numero++ + ". " + coup);
                }
            }
            System.out.println("Coups sauvegardes dans " + file.getPath());
        } catch (IOException e) {
            System.out.println("Impossible de sauvegarder les coups : " + e);
        }
    }

    private static void recordPosition(Board board, Map<String, Integer> positionHistory) {
        positionHistory.merge(board.positionKey(), 1, Integer::sum);
    }

    /*
     * Livre d'ouverture rouge : pour la ligne EXACTE de l'adversaire connu,
     * joue d'office la branche validee par la serie du 3 aout (prefixe
     * G12-B12 : 2 victoires + 1 nul, 0 defaite ; les branches M5-J5/M6-J6
     * au meme carrefour : 3 defaites eclair par tour du roi). Cle = suite
     * complete des coups joues ; a la moindre deviation adverse, aucune
     * entree ne correspond et la recherche normale reprend.
     */
    private static final String[][] OPENING_BOOK = {
        {"", "E13-C13"},
        {"E13-C13 G8-C8", "M8-G8"},
        {"E13-C13 G8-C8 M8-G8 F7-F8", "G8-K8"},
        {"E13-C13 G8-C8 M8-G8 F7-F8 G8-K8 G9-K9", "I13-K13"},
        {"E13-C13 G8-C8 M8-G8 F7-F8 G8-K8 G9-K9 I13-K13 J7-K7", "G12-B12"},
        {"E13-C13 G8-C8 M8-G8 F7-F8 G8-K8 G9-K9 I13-K13 J7-K7 G12-B12 G7-G9", "H13-H9"},
        {"E13-C13 G8-C8 M8-G8 F7-F8 G8-K8 G9-K9 I13-K13 J7-K7 G12-B12 G7-G9 H13-H9 G9-D9", "L7-L12"},
    };

    private static Move bookMove(Board board, int player, List<String> rejectedMoves, List<String> movesLog) {
        if (player != Board.RED) return null;
        StringBuilder history = new StringBuilder();
        for (String entry : movesLog) {
            // entrees du journal au format "ROUGE E13-C13" : ne garder que le coup
            if (history.length() > 0) history.append(' ');
            history.append(entry.substring(entry.lastIndexOf(' ') + 1));
        }
        String key = history.toString();
        for (String[] line : OPENING_BOOK) {
            if (line[0].equals(key)) {
                Move move = Move.tryParse(line[1]);
                if (move != null && board.isValidMove(move) && !rejectedMoves.contains(line[1])) {
                    System.out.println("(livre d'ouverture : " + line[1] + ")");
                    return move;
                }
            }
        }
        return null;
    }

    private static Move chooseMove(Board board, int player, List<String> rejectedMoves,
                                   Map<String, Integer> positionHistory, List<String> movesLog) {
        Move bookChoice = bookMove(board, player, rejectedMoves, movesLog);
        if (bookChoice != null) return bookChoice;

        Move bestMove = board.getBestMoveTimed(player, TIME_BUDGET_MS, positionHistory);
        System.out.println("(profondeur atteinte : " + Board.lastSearchDepth + ")");
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
