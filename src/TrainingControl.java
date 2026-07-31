import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class TrainingControl {
    private TrainingControl() {}

    static boolean shouldStop(Path controlPath) {
        if (Thread.currentThread().isInterrupted()) return true;
        if (controlPath == null || !Files.isRegularFile(controlPath)) return false;

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(controlPath)) {
            properties.load(input);
            String desired = properties.getProperty("desired", "running");
            return !"running".equalsIgnoreCase(desired);
        } catch (IOException e) {
            return false;
        }
    }
}
