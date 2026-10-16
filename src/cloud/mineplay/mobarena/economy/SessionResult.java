package cloud.mineplay.mobarena.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, detached data handed from the arena thread to the ledger worker. */
public record SessionResult(UUID sessionId, String arenaId, Instant startedAt, Instant finishedAt,
        int completedWaves, BigDecimal prizePot, List<Participant> participants, boolean cancelled, String endReason) {
    public SessionResult {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(arenaId);
        Objects.requireNonNull(startedAt);
        Objects.requireNonNull(finishedAt);
        Objects.requireNonNull(prizePot);
        Objects.requireNonNull(endReason);
        if (endReason.isBlank() || endReason.length() > 64) throw new IllegalArgumentException("Invalid session end reason");
        participants = List.copyOf(participants);
        if (arenaId.isBlank() || arenaId.length() > 128 || completedWaves < 0 || prizePot.signum() < 0 || finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Invalid arena result");
        }
        var ids = new HashSet<UUID>();
        for (Participant participant : participants) {
            if (!ids.add(participant.playerId())) throw new IllegalArgumentException("Duplicate participant");
        }
    }

    public SessionResult(UUID sessionId, String arenaId, Instant startedAt, Instant finishedAt,
            int completedWaves, BigDecimal prizePot, List<Participant> participants, boolean cancelled) {
        this(sessionId, arenaId, startedAt, finishedAt, completedWaves, prizePot, participants, cancelled,
                cancelled ? "CANCELLED" : "COMPLETED");
    }

    public record Participant(UUID playerId, String playerName, int kills, double damage, long participationSeconds) {
        public Participant {
            Objects.requireNonNull(playerId);
            if (playerName == null || playerName.isBlank()) playerName = playerId.toString();
            if (playerName.length() > 64) throw new IllegalArgumentException("Player name is too long");
            if (kills < 0 || !Double.isFinite(damage) || damage < 0 || participationSeconds < 0) {
                throw new IllegalArgumentException("Invalid participant contribution");
            }
        }
        public Participant(UUID playerId, int kills, double damage, long participationSeconds) {
            this(playerId, playerId.toString(), kills, damage, participationSeconds);
        }
    }
}
