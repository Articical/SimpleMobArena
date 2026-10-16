package cloud.mineplay.mobarena.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MatchDetails(UUID sessionId, String arenaId, Instant startedAt, Instant finishedAt,
        int completedWaves, long totalKills, BigDecimal prizePot, BigDecimal unallocatedPot,
        boolean cancelled, String endReason, List<MatchParticipant> participants) {
    public MatchDetails { participants = List.copyOf(participants); }
    public record MatchParticipant(UUID playerId, String playerName, int kills, double damage,
            long participationSeconds, Settlement settlement) { }
}
