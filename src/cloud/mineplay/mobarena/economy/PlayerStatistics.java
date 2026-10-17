package cloud.mineplay.mobarena.economy;

import java.math.BigDecimal;
import java.util.UUID;

public record PlayerStatistics(UUID playerId, long sessions, long kills, double damage,
        int highestWave, BigDecimal earned, String playerName) {
    public PlayerStatistics {
        if (playerName == null || playerName.isBlank()) playerName = playerId.toString();
    }

    public PlayerStatistics(UUID playerId, long sessions, long kills, double damage,
            int highestWave, BigDecimal earned) {
        this(playerId, sessions, kills, damage, highestWave, earned, playerId.toString());
    }
}
