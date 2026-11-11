package cloud.mineplay.mobarena.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    public record Participant(UUID playerId, String playerName, int kills, double damage, long participationSeconds,
                              Map<String, Integer> mobKills) {
        public Participant {
            Objects.requireNonNull(playerId);
            if (playerName == null || playerName.isBlank()) playerName = playerId.toString();
            if (playerName.length() > 64) throw new IllegalArgumentException("Player name is too long");
            if (kills < 0 || !Double.isFinite(damage) || damage < 0 || participationSeconds < 0) {
                throw new IllegalArgumentException("Invalid participant contribution");
            }
            mobKills = Map.copyOf(mobKills);
            if (mobKills.size() > 256) throw new IllegalArgumentException("Too many mob types in participant contribution");
            long typedKills = 0;
            for (var entry : mobKills.entrySet()) {
                if (!entry.getKey().matches("[A-Z][A-Z0-9_]{0,63}") || entry.getValue() < 0)
                    throw new IllegalArgumentException("Invalid per-mob kill contribution");
                typedKills += entry.getValue();
            }
            if (typedKills > kills) throw new IllegalArgumentException("Per-mob kills exceed the participant total");
        }
        /** Older match records contain only aggregate kills; never invent their mob types. */
        public Participant(UUID playerId, String playerName, int kills, double damage, long participationSeconds) {
            this(playerId, playerName, kills, damage, participationSeconds, Map.of());
        }
        public Participant(UUID playerId, int kills, double damage, long participationSeconds) {
            this(playerId, playerId.toString(), kills, damage, participationSeconds);
        }
    }
}
