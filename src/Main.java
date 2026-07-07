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
        test9_moveParseWithoutDash();
        test10_nonKingCannotMoveToCorner();
        test11_kingCaptureWithThrone();
        test12_kingCaptureWithBorder();
        test13_cpuReturnsLegalMove();
        test14_findBestMoveTimeLimit();
        test15_makeUnmakeRestoresBoard();
        test16_kingParticipatesInCapture();
        test17_redFindsImmediateKingCapture();
        test18_blackFindsImmediateEscape();
        test19_redBlocksImmediateEscape();
        test20_minMaxAndAlphaBetaAgreeAtDepth();
        test21_evaluateHasCorrectSignForWinner();
        test22_findBestMoveAtDepthWorksForBothPlayers();
        test23_redBlocksEdgeEscapeNearCorner();
        test24_redPrefersFrontLineOverChase();
        test25_applyMoveAndMakeMoveMatch();
        test26_makeUnmakeRestoresPositionKey();
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

    static void test9_moveParseWithoutDash() {
        System.out.println("Test 9 : parser un coup sans tiret");
        Move move = Move.parse("D6D5");

        boolean ok = (move.fromRow == 7 && move.fromCol == 3 && move.toRow == 8 && move.toCol == 3);
        System.out.println(ok ? "PASS : D6D5 parse correctement" : "FAIL : parsing incorrect");
    }

    static void test10_nonKingCannotMoveToCorner() {
        System.out.println("Test 10 : une piece ordinaire ne peut pas aller dans un coin");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[0][1] = Board.RED;

        Board b = new Board(g);
        Move move = new Move(0, 1, 0, 0);
        b.print();

        boolean ok = !b.isValidMove(move);
        System.out.println(ok ? "PASS : coin interdit pour non-roi" : "FAIL : piece ordinaire accepte au coin");
    }

    static void test11_kingCaptureWithThrone() {
        System.out.println("Test 11 : capture du roi avec le trone");
        int[][] g = new int[13][13];
        g[6][5] = Board.KING;    // roi adjacent au trone en G7
        g[6][4] = Board.RED;     // gauche
        g[5][5] = Board.RED;     // haut
        g[7][5] = Board.RED;     // bas

        Board b = new Board(g);
        b.print();

        boolean ok = (b.getWinner() == Board.RED);
        System.out.println(ok ? "PASS : roi capture avec le trone" : "FAIL : roi non capture avec le trone");
    }

    static void test12_kingCaptureWithBorder() {
        System.out.println("Test 12 : capture du roi avec la bordure");
        int[][] g = new int[13][13];
        g[0][6] = Board.KING;    // roi sur la bordure du haut
        g[0][5] = Board.RED;     // gauche
        g[0][7] = Board.RED;     // droite
        g[1][6] = Board.RED;     // bas

        Board b = new Board(g);
        b.print();

        boolean ok = (b.getWinner() == Board.RED);
        System.out.println(ok ? "PASS : roi capture avec la bordure" : "FAIL : roi non capture avec la bordure");
    }

    static void test13_cpuReturnsLegalMove() {
        System.out.println("Test 13 : CPU retourne un coup legal");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[0][3] = Board.RED;
        g[1][6] = Board.BLACK;

        Board b = new Board(g);
        CPUPlayer cpu = new CPUPlayer(Board.RED);
        Move move = cpu.findBestMove(b, 100);

        boolean ok = (move != null && b.isValidMove(move));
        System.out.println(ok ? "PASS : coup CPU legal -> " + move : "FAIL : coup CPU invalide");
    }

    static void test14_findBestMoveTimeLimit() {
        System.out.println("Test 14 : CPU respecte une limite de temps courte");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[0][3] = Board.RED;
        g[1][6] = Board.BLACK;

        Board b = new Board(g);
        CPUPlayer cpu = new CPUPlayer(Board.RED);

        long start = System.currentTimeMillis();
        Move move = cpu.findBestMove(b, 500);
        long elapsed = System.currentTimeMillis() - start;

        boolean ok = (move != null && b.isValidMove(move) && elapsed < 650);
        System.out.println(ok ? "PASS : temps " + elapsed + "ms, coup " + move : "FAIL : temps " + elapsed + "ms");
    }

    static void test15_makeUnmakeRestoresBoard() {
        System.out.println("Test 15 : makeMove/unmakeMove restaure le plateau");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[3][4] = Board.BLACK;
        g[3][3] = Board.RED;
        g[5][5] = Board.RED;

        Board b = new Board(g);
        long hashBefore = b.computeHash();
        Move move = new Move(5, 5, 3, 5);

        MoveUndo undo = b.makeMove(move);
        boolean captured = (b.grid[3][4] == Board.EMPTY);
        b.unmakeMove(move, undo);

        boolean restored = b.computeHash() == hashBefore
                && b.grid[3][4] == Board.BLACK
                && b.grid[5][5] == Board.RED
                && b.grid[3][5] == Board.EMPTY;

        System.out.println(captured && restored ? "PASS : plateau restaure" : "FAIL : restauration incorrecte");
    }

    static void test16_kingParticipatesInCapture() {
        System.out.println("Test 16 : le roi participe aux captures");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;    // G7
        g[5][6] = Board.BLACK;   // G8
        g[4][12] = Board.RED;    // M9

        Board b = new Board(g);
        b.applyMove(new Move(4, 12, 4, 6));  // M9-G9

        Move kingMove = new Move(6, 6, 5, 6); // G7-G8
        boolean ok = b.grid[5][6] == Board.EMPTY && b.isValidMove(kingMove);

        System.out.println(ok ? "PASS : G8 libere par capture avec le roi" : "FAIL : le roi ne participe pas a la capture");
    }

    static void test17_redFindsImmediateKingCapture() {
        System.out.println("Test 17 : rouge trouve une capture immediate du roi");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[5][6] = Board.RED;
        g[7][6] = Board.RED;
        g[6][5] = Board.RED;
        g[6][10] = Board.RED;

        Board b = new Board(g);
        CPUPlayer cpu = new CPUPlayer(Board.RED);
        Move move = cpu.findBestMoveAtDepth(b, 1);

        boolean ok = false;
        if (move != null && b.isValidMove(move)) {
            b.applyMove(move);
            ok = b.getWinner() == Board.RED;
        }

        System.out.println(ok ? "PASS : rouge capture le roi avec " + move : "FAIL : rouge ne choisit pas la capture");
    }

    static void test18_blackFindsImmediateEscape() {
        System.out.println("Test 18 : noir trouve une fuite immediate");
        int[][] g = new int[13][13];
        g[0][5] = Board.KING;
        g[6][6] = Board.RED;

        Board b = new Board(g);
        CPUPlayer cpu = new CPUPlayer(Board.BLACK);
        Move move = cpu.findBestMoveAtDepth(b, 1);

        boolean ok = false;
        if (move != null && b.isValidMove(move)) {
            b.applyMove(move);
            ok = b.getWinner() == Board.BLACK;
        }

        System.out.println(ok ? "PASS : noir echappe le roi avec " + move : "FAIL : noir ne choisit pas la fuite");
    }

    static void test19_redBlocksImmediateEscape() {
        System.out.println("Test 19 : rouge bloque une fuite immediate");
        int[][] g = new int[13][13];
        g[0][5] = Board.KING;    // F13, ligne ouverte vers A13
        g[0][6] = Board.RED;     // bloque deja la fuite vers M13
        g[2][2] = Board.RED;     // peut monter en C13 pour fermer A13

        Board b = new Board(g);
        CPUPlayer cpu = new CPUPlayer(Board.RED);
        Move move = cpu.findBestMoveAtDepth(b, 2);

        boolean ok = false;
        if (move != null && b.isValidMove(move)) {
            b.applyMove(move);
            ok = b.grid[0][2] == Board.RED && b.getWinner() == 0;
        }

        System.out.println(ok ? "PASS : rouge bloque le coin avec " + move : "FAIL : rouge laisse la fuite ouverte");
    }

    static void test20_minMaxAndAlphaBetaAgreeAtDepth() {
        System.out.println("Test 20 : minMax et alphaBeta donnent le meme score");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[5][6] = Board.RED;
        g[7][6] = Board.RED;
        g[6][5] = Board.RED;
        g[6][10] = Board.RED;
        g[3][3] = Board.BLACK;

        Board b = new Board(g);
        CPUPlayer cpu = new CPUPlayer(Board.RED);

        int alphaBetaScore = cpu.evaluateBestMoveAtDepth(b, 2);
        int minMaxScore = cpu.evaluateBestMoveAtDepthMinMax(b, 2);
        Move alphaBetaMove = cpu.findBestMoveAtDepth(b, 2);
        Move minMaxMove = cpu.findBestMoveAtDepthMinMax(b, 2);

        boolean ok = alphaBetaScore == minMaxScore && sameMove(alphaBetaMove, minMaxMove);
        System.out.println(ok ? "PASS : score " + alphaBetaScore + ", coup " + alphaBetaMove
                : "FAIL : alphaBeta=" + alphaBetaScore + "/" + alphaBetaMove + ", minMax=" + minMaxScore + "/" + minMaxMove);
    }

    static void test21_evaluateHasCorrectSignForWinner() {
        System.out.println("Test 21 : evaluate donne le bon signe pour une victoire");
        int[][] blackWinGrid = new int[13][13];
        blackWinGrid[0][0] = Board.KING;
        Board blackWin = new Board(blackWinGrid);

        int[][] redWinGrid = new int[13][13];
        redWinGrid[6][6] = Board.KING;
        redWinGrid[5][6] = Board.RED;
        redWinGrid[7][6] = Board.RED;
        redWinGrid[6][5] = Board.RED;
        redWinGrid[6][7] = Board.RED;
        Board redWin = new Board(redWinGrid);

        boolean ok = blackWin.evaluate(Board.BLACK) > 0
                && blackWin.evaluate(Board.RED) < 0
                && redWin.evaluate(Board.RED) > 0
                && redWin.evaluate(Board.BLACK) < 0
                && blackWin.evaluate(Board.BLACK) == -blackWin.evaluate(Board.RED)
                && redWin.evaluate(Board.RED) == -redWin.evaluate(Board.BLACK);

        System.out.println(ok ? "PASS : signes coherents" : "FAIL : signes evaluate incoherents");
    }

    static void test22_findBestMoveAtDepthWorksForBothPlayers() {
        System.out.println("Test 22 : findBestMoveAtDepth fonctionne rouge et noir");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[0][3] = Board.RED;
        g[1][6] = Board.BLACK;

        Board redBoard = new Board(copyGrid(g));
        Board blackBoard = new Board(copyGrid(g));
        CPUPlayer redCpu = new CPUPlayer(Board.RED);
        CPUPlayer blackCpu = new CPUPlayer(Board.BLACK);

        Move redMove = redCpu.findBestMoveAtDepth(redBoard, 2);
        Move blackMove = blackCpu.findBestMoveAtDepth(blackBoard, 2);

        boolean ok = redMove != null && redBoard.isValidMove(redMove)
                && blackMove != null && blackBoard.isValidMove(blackMove);

        System.out.println(ok ? "PASS : rouge " + redMove + ", noir " + blackMove : "FAIL : coup profondeur invalide");
    }

    static void test23_redBlocksEdgeEscapeNearCorner() {
        System.out.println("Test 23 : rouge bloque une fuite sur la bordure pres du coin");
        int[][] g = new int[13][13];
        g[12][10] = Board.KING;  // K1, proche du coin M1
        g[12][9] = Board.RED;    // ferme la fuite vers A1
        g[10][11] = Board.RED;   // L3 peut descendre en L1
        g[6][6] = Board.RED;

        Board b = new Board(g);
        CPUPlayer cpu = new CPUPlayer(Board.RED);
        Move move = cpu.findBestMoveAtDepth(b, 2);

        boolean ok = false;
        if (move != null && b.isValidMove(move)) {
            b.applyMove(move);
            ok = b.grid[12][11] == Board.RED && b.getWinner() != Board.BLACK;
        }

        System.out.println(ok ? "PASS : rouge ferme la fuite avec " + move : "FAIL : rouge ne ferme pas la fuite de bord");
    }

    static void test24_redPrefersFrontLineOverChase() {
        System.out.println("Test 24 : rouge prefere une ligne de front au lieu de seulement chasser");
        int[][] g = new int[13][13];
        g[3][3] = Board.KING;    // D10, derniere approche claire vers D13
        g[3][0] = Board.RED;     // ferme A10
        g[3][12] = Board.RED;    // ferme M10
        g[12][3] = Board.RED;    // ferme D1
        g[6][3] = Board.RED;     // peut chasser verticalement
        g[0][1] = Board.RED;     // peut occuper D13 comme front line
        g[6][6] = Board.RED;

        Board b = new Board(g);
        CPUPlayer cpu = new CPUPlayer(Board.RED);
        Move move = cpu.findBestMoveAtDepth(b, 2);

        boolean ok = false;
        if (move != null && b.isValidMove(move)) {
            b.applyMove(move);
            ok = b.grid[0][3] == Board.RED;
        }

        System.out.println(ok ? "PASS : rouge construit une ligne de front avec " + move : "FAIL : rouge ne construit pas la ligne de front");
    }

    static void test25_applyMoveAndMakeMoveMatch() {
        System.out.println("Test 25 : applyMove et makeMove produisent le meme plateau");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[3][4] = Board.BLACK;
        g[3][3] = Board.RED;
        g[5][5] = Board.RED;

        Move move = new Move(5, 5, 3, 5);
        Board applied = new Board(copyGrid(g));
        Board made = new Board(copyGrid(g));

        applied.applyMove(move);
        made.makeMove(move);

        boolean ok = applied.positionKey().equals(made.positionKey())
                && applied.getKingRow() == made.getKingRow()
                && applied.getKingCol() == made.getKingCol();

        System.out.println(ok ? "PASS : plateaux identiques" : "FAIL : applyMove et makeMove divergent");
    }

    static void test26_makeUnmakeRestoresPositionKey() {
        System.out.println("Test 26 : makeMove/unmakeMove restaure la cle de position");
        int[][] g = new int[13][13];
        g[6][6] = Board.KING;
        g[3][4] = Board.BLACK;
        g[3][3] = Board.RED;
        g[5][5] = Board.RED;

        Board b = new Board(g);
        String keyBefore = b.positionKey();
        long hashBefore = b.computeHash();
        Move move = new Move(5, 5, 3, 5);

        MoveUndo undo = b.makeMove(move);
        b.unmakeMove(move, undo);

        boolean ok = keyBefore.equals(b.positionKey()) && hashBefore == b.computeHash();
        System.out.println(ok ? "PASS : cle et hash restaures" : "FAIL : cle ou hash different");
    }

    static boolean sameMove(Move a, Move b) {
        if (a == null || b == null) {
            return a == b;
        }

        return a.fromRow == b.fromRow
                && a.fromCol == b.fromCol
                && a.toRow == b.toRow
                && a.toCol == b.toCol;
    }

    static int[][] copyGrid(int[][] original) {
        int[][] copy = new int[13][13];

        for (int row = 0; row < 13; row++) {
            for (int col = 0; col < 13; col++) {
                copy[row][col] = original[row][col];
            }
        }

        return copy;
    }
}
