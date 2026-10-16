package cloud.mineplay.mobarena.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MatchPerformance(UUID sessionId, String arenaId, Instant startedAt, Instant finishedAt,
        int completedWaves, boolean cancelled, int kills, double damage, long participationSeconds,
        BigDecimal share, BigDecimal grossAmount, BigDecimal intendedAmount, BigDecimal confirmedAmount,
        String capsApplied, Settlement.Status status, Instant paidAt, UUID settlementId) { }
