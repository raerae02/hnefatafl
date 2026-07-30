import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) {
        test1_captureSimple();
        test2_captureDouble();
        test3_captureContreTrone();
        test4_roiCapture();
        test5_roiAuCoin();
        test6_captureActiveSeulement();
        test7_bordNeCapturePas();
        test8_roiSandwichPasCapture();
        test9_minimaxAlphaBetaMemeScore();
        test10_iaPrendVictoireImmediate();
        test11_rechercheRestaurePlateau();
        test12_timeoutRestaurePlateau();
        test13_tableTranspositionReutilisee();
        test14_rechercheParallelePrendVictoire();
        test15_ponderingSarreteProprement();
    }

    static void test1_captureSimple() {
        System.out.println("Test 1 : capture simple");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;    // roi
        g[3][4] = Board.BLACK;   // victime
        g[3][3] = Board.RED;     // mâchoire déjà en place
        g[5][5] = Board.RED;     // le rouge qui va bouger

        Board b = new Board(g);
        b.print();
        b.applyMove(new Move(5, 5, 3, 5));   // le rouge monte à côté de la victime
        b.print();

        boolean ok = (b.grid[3][4] == Board.EMPTY);
        System.out.println(ok ? "PASS : victime capturée" : "FAIL : victime encore là");
    }

    static void test2_captureDouble() {
        System.out.println("Test 2 : capture double");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;    // roi
        g[3][4] = Board.BLACK;   // victime
        g[3][6] = Board.BLACK;   // victime
        g[3][3] = Board.RED;     // mâchoire déjà en place
        g[3][7] = Board.RED;     // mâchoire déjà en place
        g[5][5] = Board.RED;     // le rouge qui va bouger

        Board b = new Board(g);
        b.print();
        b.applyMove(new Move(5, 5, 3, 5));   // le rouge monte à côté de deux victimes
        b.print();

        boolean ok = (b.grid[3][4] == Board.EMPTY && b.grid[3][6] == Board.EMPTY);
        System.out.println(ok ? "PASS : les 2 victimes sont capturées" : "FAIL : victimes sont encore là");
    }

    static void test3_captureContreTrone() {
        System.out.println("Test 3 : capture contre trone");
        int[][] g = new int[13][13];
        g[0][5] = Board.KING;    // roi
        g[6][7] = Board.BLACK;   // victime SIXXXXXXXXXXXXXX SEVENNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNN
        g[7][7] = Board.RED;     // mâchoire déjà en place
        g[5][7] = Board.RED;     // mâchoire déjà en place
        g[6][10] = Board.RED;    // le rouge qui va bouger

        Board b = new Board(g);
        b.print();
        b.applyMove(new Move(6, 10, 6, 8));   // le rouge monte à côté du roi
        b.print();

        boolean ok = (b.grid[6][7] == Board.EMPTY);
        System.out.println(ok ? "PASS : victime capturé" : "FAIL : victime encore la");
    }

    static void test4_roiCapture() {
        System.out.println("Test 4 : capture contre roi");
        int[][] g = new int[13][13];
        g[6][7] = Board.KING;    // roi
        g[5][7] = Board.RED;     // mâchoire déjà en place
        g[7][7] = Board.RED;     // mâchoire déjà en place
        g[6][10] = Board.RED;    // le rouge qui va bouger

        Board b = new Board(g);
        b.print();
        b.applyMove(new Move(6, 10, 6, 8));   // le rouge monte à côté du roi
        b.print();

        boolean ok = (b.getWinner() == Board.RED);
        System.out.println(ok ? "PASS : roi capturé" : "FAIL : roi encore la");
    }

    static void test5_roiAuCoin() {
        System.out.println("Test 5 : roi atteint un coin -> victoire noire");
        int[][] g = new int[13][13];
        g[0][5] = Board.KING;

        Board b = new Board(g);
        b.applyMove(new Move(0, 5, 0, 0));
        b.print();

        boolean ok = (b.getWinner() == Board.BLACK);
        System.out.println(ok ? "PASS : victoire noire" : "FAIL");
    }

    static void test6_captureActiveSeulement() {
        System.out.println("Test 6 : pas de capture passive (se placer soi-meme en sandwich)");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[3][3] = Board.RED;     // deux rouges avec un trou entre eux
        g[3][5] = Board.RED;
        g[8][4] = Board.BLACK;   // le noir qui va se jeter dans le trou

        Board b = new Board(g);
        b.applyMove(new Move(8, 4, 3, 4));   // noir se place LUI-MEME entre les deux rouges
        b.print();

        boolean ok = (b.grid[3][4] == Board.BLACK);   // il doit survivre
        System.out.println(ok ? "PASS : pas de capture passive" : "FAIL : capture passive (bug!)");
    }

    static void test7_bordNeCapturePas() {
        System.out.println("Test 7 : le bord ne capture pas un pion ordinaire");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[0][4] = Board.BLACK;   // noir collé au bord du haut (E13)
        g[2][4] = Board.RED;     // le rouge qui va monter sous lui

        Board b = new Board(g);
        b.applyMove(new Move(2, 4, 1, 4));   // rouge arrive sous le noir : bord au-dessus
        b.print();

        boolean ok = (b.grid[0][4] == Board.BLACK);   // le noir survit
        System.out.println(ok ? "PASS : bord non capturant" : "FAIL");
    }

    static void test8_roiSandwichPasCapture() {
        System.out.println("Test 8 : roi en sandwich a 2 -> PAS capture (il en faut 4)");
        int[][] g = new int[13][13];
        g[6][5] = Board.KING;    // roi en F7, collé au trône (G7)
        g[6][2] = Board.RED;     // le rouge qui va arriver de l'autre côté

        Board b = new Board(g);
        b.applyMove(new Move(6, 2, 6, 4));   // rouge en E7 : sandwich rouge-roi-trône
        b.print();

        boolean ok = (b.getWinner() == 0);   // partie PAS finie : haut et bas du roi sont libres
        System.out.println(ok ? "PASS : roi survit au sandwich" : "FAIL : roi capturé a 2 (bug!)");
    }

    static void test9_minimaxAlphaBetaMemeScore() {
        System.out.println("Test 9 : minimax et alpha-beta donnent le meme score");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[6][4] = Board.BLACK;
        g[4][6] = Board.BLACK;
        g[6][2] = Board.RED;
        g[2][6] = Board.RED;

        Board b = new Board(g);

        int minimaxScore = b.minimax(2, Board.BLACK, Board.BLACK);
        int alphaBetaScore = b.minimaxAlphaBeta(2, Board.BLACK, Board.BLACK);
        boolean ok = minimaxScore == alphaBetaScore;

        System.out.println(ok ? "PASS : scores identiques" : "FAIL : scores differents");
    }

    static void test10_iaPrendVictoireImmediate() {
        System.out.println("Test 10 : IA choisit une victoire immediate");
        int[][] g = new int[13][13];
        g[0][5] = Board.KING;
        g[5][5] = Board.RED;

        Board b = new Board(g);
        Move bestMove = b.getBestMoveTimed(Board.BLACK, 100, null);
        boolean ok = bestMove != null && bestMove.toRow == 0 && bestMove.toCol == 0;

        System.out.println(ok ? "PASS : roi va au coin" : "FAIL : meilleur coup inattendu");
    }

    static void test11_rechercheRestaurePlateau() {
        System.out.println("Test 11 : la recherche restaure le plateau");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[3][4] = Board.BLACK;
        g[3][6] = Board.BLACK;
        g[3][3] = Board.RED;
        g[3][7] = Board.RED;
        g[5][5] = Board.RED;

        Board b = new Board(g);
        String initialPosition = b.positionKey();
        int initialRedEvaluation = b.evaluate(Board.RED);
        int initialBlackEvaluation = b.evaluate(Board.BLACK);

        b.minimaxAlphaBeta(2, Board.RED, Board.RED);

        boolean ok = initialPosition.equals(b.positionKey())
                && initialRedEvaluation == b.evaluate(Board.RED)
                && initialBlackEvaluation == b.evaluate(Board.BLACK);
        System.out.println(ok ? "PASS : plateau restaure" : "FAIL : la recherche a modifie le plateau");
    }

    static void test12_timeoutRestaurePlateau() {
        System.out.println("Test 12 : un timeout restaure le plateau");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[6][4] = Board.BLACK;
        g[4][6] = Board.BLACK;
        g[6][2] = Board.RED;
        g[2][6] = Board.RED;

        Board b = new Board(g);
        String initialPosition = b.positionKey();
        int initialEvaluation = b.evaluate(Board.RED);

        b.getBestMoveTimed(Board.RED, 0, null);

        boolean ok = initialPosition.equals(b.positionKey())
                && initialEvaluation == b.evaluate(Board.RED);
        System.out.println(ok ? "PASS : plateau restaure apres timeout"
                : "FAIL : le timeout a laisse un coup simule");
    }

    static void test13_tableTranspositionReutilisee() {
        System.out.println("Test 13 : table de transposition reutilisee");
        int[][] g = new int[13][13];
        g[0][5] = Board.KING;
        g[5][5] = Board.RED;

        Board b = new Board(g);
        TranspositionTable table = new TranspositionTable(12);

        SearchContext firstContext = SearchContext.timed(
                Board.BLACK, 50, table, null, false);
        SearchResult first = b.searchBestMove(Board.BLACK, null, firstContext);

        SearchContext secondContext = SearchContext.timed(
                Board.BLACK, 50, table, null, false);
        SearchResult second = b.searchBestMove(Board.BLACK, null, secondContext);

        boolean ok = first.bestMove != null
                && first.bestMove.equals(second.bestMove)
                && first.bestScore == second.bestScore
                && second.tableHits > 0;
        System.out.println(ok ? "PASS : resultats reutilises"
                : "FAIL : table non reutilisee ou resultat different");
    }

    static void test14_rechercheParallelePrendVictoire() {
        System.out.println("Test 14 : recherche parallele choisit la victoire");
        int[][] g = new int[13][13];
        g[0][5] = Board.KING;
        g[5][5] = Board.RED;

        Board b = new Board(g);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            SearchContext context = SearchContext.timed(
                    Board.BLACK, 100, new TranspositionTable(12), executor, true);
            SearchResult result = b.searchBestMove(Board.BLACK, null, context);
            boolean ok = result.bestMove != null
                    && result.bestMove.toRow == 0
                    && (result.bestMove.toCol == 0 || result.bestMove.toCol == 12);
            System.out.println(ok ? "PASS : victoire trouvee en parallele"
                    : "FAIL : meilleur coup parallele inattendu");
        } finally {
            executor.shutdownNow();
        }
    }

    static void test15_ponderingSarreteProprement() {
        System.out.println("Test 15 : pondering interruptible");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[6][4] = Board.BLACK;
        g[4][6] = Board.BLACK;
        g[6][2] = Board.RED;
        g[2][6] = Board.RED;

        Board b = new Board(g);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        SearchContext context = SearchContext.pondering(
                Board.RED, new TranspositionTable(12), executor, true);
        Thread pondering = new Thread(
                () -> b.searchBestMove(Board.BLACK, null, context));

        try {
            pondering.start();
            Thread.sleep(25);
            context.requestStop();
            pondering.interrupt();
            pondering.join(1000);
            System.out.println(!pondering.isAlive()
                    ? "PASS : pondering arrete"
                    : "FAIL : pondering toujours actif");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("FAIL : test interrompu");
        } finally {
            context.requestStop();
            executor.shutdownNow();
        }
    }
}
