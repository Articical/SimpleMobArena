package cloud.mineplay.mobarena.economy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable totals read from committed arena records, ready for an external achievement provider. */
public record AchievementProgress(UUID playerId, String playerName, Map<String, Long> totals) {
    public AchievementProgress {
        Objects.requireNonNull(playerId);
        if (playerName == null || playerName.isBlank()) playerName = playerId.toString();
        totals = Map.copyOf(totals);
        for (var entry : totals.entrySet()) {
            if (!entry.getKey().matches("[a-z][a-z0-9_]{0,95}") || entry.getValue() < 0)
                throw new IllegalArgumentException("Invalid achievement progress total");
        }
    }
}
