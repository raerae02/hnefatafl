import java.util.List;

final class SelfPlayGame {
    final long seed;
    final int outcome;
    final List<TrainingSample> samples;

    SelfPlayGame(long seed, int outcome, List<TrainingSample> samples) {
        this.seed = seed;
        this.outcome = outcome;
        this.samples = samples;
    }
}
