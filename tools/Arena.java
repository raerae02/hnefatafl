import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/*
 * Banc d'A/B testing : fait s'affronter deux versions compilees du moteur.
 * Usage : java Arena <classesA> <classesB> [parties] [budgetMs]
 *
 * Chaque version est chargee dans son propre ClassLoader (statiques isolees,
 * table de transposition comprise). A et B alternent les couleurs. La version
 * courante sert d'arbitre : elle valide chaque coup et detecte la fin -
 * un coup illegal revele une divergence de regles entre versions.
 */
public class Arena {

    static class Engine {
        final String dir;
        final Object board;
        final Map<String, Integer> history = new HashMap<>();
        final Method getBestMoveTimed, applyMove, positionKey, tryParse;

        Engine(String dir, int[][] grid) throws Exception {
            this.dir = dir;
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{ Paths.get(dir).toUri().toURL() },
                    ClassLoader.getPlatformClassLoader());
            Class<?> boardClass = loader.loadClass("Board");
            Class<?> moveClass = loader.loadClass("Move");

            Constructor<?> ctor = boardClass.getDeclaredConstructor(int[][].class);
            ctor.setAccessible(true);
            board = ctor.newInstance((Object) grid);

            getBestMoveTimed = boardClass.getDeclaredMethod("getBestMoveTimed", int.class, long.class, Map.class);
            applyMove = boardClass.getDeclaredMethod("applyMove", moveClass);
            positionKey = boardClass.getDeclaredMethod("positionKey");
            tryParse = moveClass.getDeclaredMethod("tryParse", String.class);
            getBestMoveTimed.setAccessible(true);
            applyMove.setAccessible(true);
            positionKey.setAccessible(true);
            tryParse.setAccessible(true);
        }

        String go(int player, long budgetMs) throws Exception {
            Object move = getBestMoveTimed.invoke(board, player, budgetMs, history);
            return move == null ? null : move.toString();
        }

        void apply(String moveText) throws Exception {
            Object move = tryParse.invoke(null, moveText);
            applyMove.invoke(board, move);
            history.merge((String) positionKey.invoke(board), 1, Integer::sum);
        }
    }

    // 4 demi-coups aleatoires mais legaux, reproductibles par numero de paire.
    static List<String> randomOpening(int pairIndex) {
        Random random = new Random(1000L + pairIndex);
        Board board = new Board(Simulation.initialGrid());
        List<String> opening = new ArrayList<>();
        int player = Board.RED;

        for (int i = 0; i < 4; i++) {
            List<Move> moves = board.getLegalMoves(player);
            Move move = moves.get(random.nextInt(moves.size()));
            opening.add(move.toString());
            board.applyMove(move);
            if (board.isTerminal()) break;
            player = (player == Board.RED) ? Board.BLACK : Board.RED;
        }
        return opening;
    }

    public static void main(String[] args) throws Exception {
        String dirA = args[0], dirB = args[1];
        int games = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        long budget = args.length > 3 ? Long.parseLong(args[3]) : 400;

        int aWins = 0, bWins = 0, draws = 0;
        int aRedWins = 0, aBlackWins = 0;
        int totalPlies = 0;

        for (int game = 0; game < games; game++) {
            boolean aPlaysRed = (game % 2 == 0);
            Engine red = new Engine(aPlaysRed ? dirA : dirB, Simulation.initialGrid());
            Engine black = new Engine(aPlaysRed ? dirB : dirA, Simulation.initialGrid());
            Board referee = new Board(Simulation.initialGrid());

            int player = Board.RED;
            int result = 0;
            int plies = 0;

            // Ouverture variee : les deux parties d'une paire (couleurs inversees)
            // partent de la meme sequence de coups aleatoires. Sans cela, deux
            // moteurs deterministes rejouent sans cesse les memes parties.
            List<String> opening = randomOpening(game / 2);
            for (String openingMove : opening) {
                Move move = Move.tryParse(openingMove);
                referee.applyMove(move);
                red.apply(openingMove);
                black.apply(openingMove);
                player = (player == Board.RED) ? Board.BLACK : Board.RED;
            }

            while (plies < 300) {
                Engine mover = (player == Board.RED) ? red : black;
                String moveText = mover.go(player, budget);
                if (moveText == null) break;   // aucun coup a jouer : nul

                Move move = Move.tryParse(moveText);
                if (move == null || !referee.isValidMove(move)) {
                    System.out.println("!!! COUP ILLEGAL de " + mover.dir + " : " + moveText);
                    result = (player == Board.RED) ? Board.BLACK : Board.RED;
                    break;
                }

                referee.applyMove(move);
                red.apply(moveText);
                black.apply(moveText);
                plies++;

                if (referee.isTerminal()) {
                    result = referee.getWinner();
                    break;
                }
                player = (player == Board.RED) ? Board.BLACK : Board.RED;
            }

            totalPlies += plies;
            String outcome;
            if (result == 0) {
                draws++;
                outcome = "NUL";
            } else if ((result == Board.RED) == aPlaysRed) {
                aWins++;
                if (aPlaysRed) aRedWins++; else aBlackWins++;
                outcome = "A gagne";
            } else {
                bWins++;
                outcome = "B gagne";
            }
            System.out.printf("Partie %2d : A joue %s -> %s (%d coups)%n",
                    game + 1, aPlaysRed ? "ROUGE" : "NOIR ", outcome, plies);
        }

        System.out.println();
        System.out.println("A = " + dirA);
        System.out.println("B = " + dirB);
        System.out.printf("A : %d victoires (%d en rouge, %d en noir) | B : %d victoires | Nuls : %d / %d (moyenne %d coups)%n",
                aWins, aRedWins, aBlackWins, bWins, draws, games, totalPlies / games);
    }
}
