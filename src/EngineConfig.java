import java.io.IOException;
import java.nio.file.Path;
final class EngineConfig {
    private static final WinningKnowledgeBase KNOWLEDGE = loadKnowledge();

    private EngineConfig() {}

    static CPUPlayer createPlayer(int side) {
        return new CPUPlayer(side, KNOWLEDGE);
    }

    static WinningKnowledgeBase knowledge() { return KNOWLEDGE; }

    private static WinningKnowledgeBase loadKnowledge() {
        String path = System.getProperty("hnefatafl.knowledge");
        if (path == null || path.isBlank()) return new WinningKnowledgeBase();
        try { return WinningKnowledgeBase.load(Path.of(path)); }
        catch (IOException e) {
            System.err.println("Could not load winning knowledge: " + e.getMessage());
            return new WinningKnowledgeBase();
        }
    }
}
