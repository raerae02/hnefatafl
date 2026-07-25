import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/*
 * Simule des parties entre notre IA et un bot aleatoire (equivalent du niveau 1).
 * Permet de verifier localement que l'IA gagne en tant que rouge ET en tant que noir.
 */
public class Simulation {
    private static final int GAMES_PER_SIDE = 25;
    private static final int MAX_PLIES = 300;
    private static final long TIME_BUDGET_MS = 250;   // reduit pour garder les tests rapides

    public static void main(String[] args) {
        // Optionnel : java Simulation <seed>
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42;
        Random random = new Random(seed);

        System.out.println("=== IA joue ROUGE contre bot aleatoire ===");
        runSeries(Board.RED, random);

        System.out.println("\n=== IA joue NOIR contre bot aleatoire ===");
        runSeries(Board.BLACK, random);
    }

    private static void runSeries(int aiPlayer, Random random) {
        int wins = 0, losses = 0, draws = 0;
        long slowestMove = 0;
        int totalPlies = 0;

        for (int game = 0; game < GAMES_PER_SIDE; game++) {
            Board board = new Board(initialGrid());
            int currentPlayer = Board.RED;   // les rouges jouent en premier
            int result = 0;                  // 0 = nul
            int plies = 0;
            Map<String, Integer> positionHistory = new HashMap<>();

            while (plies < MAX_PLIES) {
                Move move;
                if (currentPlayer == aiPlayer) {
                    long start = System.currentTimeMillis();
                    move = board.getBestMoveTimed(aiPlayer, TIME_BUDGET_MS, positionHistory);
                    slowestMove = Math.max(slowestMove, System.currentTimeMillis() - start);
                } else {
                    move = randomBotMove(board, currentPlayer, random);
                }

                if (move == null) break;     // aucun coup : match nul

                board.applyMove(move);
                positionHistory.merge(board.positionKey(), 1, Integer::sum);
                plies++;

                if (board.isTerminal()) {
                    result = board.getWinner();
                    break;
                }
                currentPlayer = (currentPlayer == Board.RED) ? Board.BLACK : Board.RED;
            }

            if (result == 0) draws++;
            else if (result == aiPlayer || (aiPlayer == Board.BLACK && result == Board.BLACK)) wins++;
            else losses++;
            totalPlies += plies;

            System.out.printf("Partie %2d : %s (%d coups)%n", game + 1,
                    result == 0 ? "NUL" : (result == aiPlayer ? "VICTOIRE" : "DEFAITE"), plies);
        }

        System.out.printf("Bilan : %d victoires, %d defaites, %d nuls / %d parties (moyenne %d coups)%n",
                wins, losses, draws, GAMES_PER_SIDE, totalPlies / GAMES_PER_SIDE);
        System.out.println("Coup le plus lent : " + slowestMove + " ms (limite 5000 ms)");
    }

    // Modele du niveau 1 : prend une victoire immediate, sinon une capture
    // au hasard si possible, sinon un coup au hasard.
    private static Move randomBotMove(Board board, int player, Random random) {
        List<Move> moves = board.getLegalMoves(player);
        if (moves.isEmpty()) return null;

        List<Move> captures = new ArrayList<>();
        for (Move move : moves) {
            Board test = board.copy();
            test.applyMove(move);
            if (test.getWinner() == player) return move;
            if (countPieces(test, opponentOf(player)) < countPieces(board, opponentOf(player))) {
                captures.add(move);
            }
        }

        if (!captures.isEmpty()) return captures.get(random.nextInt(captures.size()));
        return moves.get(random.nextInt(moves.size()));
    }

    private static int opponentOf(int player) {
        return player == Board.RED ? Board.BLACK : Board.RED;
    }

    private static int countPieces(Board board, int player) {
        int count = 0;
        for (int row = 0; row < 13; row++) {
            for (int col = 0; col < 13; col++) {
                if (board.grid[row][col] == player) count++;
            }
        }
        return count;
    }

    // Position de depart officielle du plateau 13x13 (enonce du laboratoire).
    static int[][] initialGrid() {
        int[][] g = new int[13][13];
        String[] rows = {
                "....RRRRR....",   // rangee 13
                "......R......",   // rangee 12
                ".............",
                "......N......",   // rangee 10
                "R.....N.....R",
                "R.....N.....R",
                "RR.NNNKNNN.RR",   // rangee 7 (roi au centre)
                "R.....N.....R",
                "R.....N.....R",
                "......N......",   // rangee 4
                ".............",
                "......R......",   // rangee 2
                "....RRRRR...."    // rangee 1
        };
        for (int row = 0; row < 13; row++) {
            for (int col = 0; col < 13; col++) {
                char c = rows[row].charAt(col);
                if (c == 'R') g[row][col] = Board.RED;
                if (c == 'N') g[row][col] = Board.BLACK;
                if (c == 'K') g[row][col] = Board.KING;
            }
        }
        return g;
    }
}
