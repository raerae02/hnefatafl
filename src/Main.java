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
        Move bestMove = b.getBestMove(Board.BLACK, 1);
        boolean ok = bestMove != null && bestMove.toRow == 0 && bestMove.toCol == 0;

        System.out.println(ok ? "PASS : roi va au coin" : "FAIL : meilleur coup inattendu");
    }
}
