package cloud.mineplay.mobarena.economy;

import java.math.BigDecimal;
import java.util.UUID;

public record PlayerStatistics(UUID playerId, long sessions, long kills, double damage,
        int highestWave, BigDecimal earned) { }
