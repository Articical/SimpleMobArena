package cloud.mineplay.mobarena.integration;

import cloud.mineplay.mobarena.economy.AchievementProgress;
import java.util.UUID;

/** Keeps optional provider classes out of the plugin's core lifecycle signatures. */
public interface AchievementIntegration extends AutoCloseable {
    void start();
    boolean requestSync(UUID player);
    void reportLive(AchievementProgress progress);
    String status();
    @Override void close();
}
