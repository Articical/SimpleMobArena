package cloud.mineplay.mobarena.duels;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Detached PvP result. Duel contributions never enter the mob-kill or payout ledger. */
public record DuelResult(UUID sessionId, String arenaId, String mode, Instant startedAt, Instant finishedAt,
                         String winner, boolean cancelled, String endReason, List<Participant> participants) {
    public DuelResult {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        if (arenaId == null || arenaId.isBlank() || arenaId.length() > 128
                || mode == null || !Set.of("DUEL", "TEAM_DUEL").contains(mode)
                || winner == null || !Set.of("RED", "BLUE", "DRAW").contains(winner)
                || endReason == null || endReason.isBlank() || endReason.length() > 64
                || finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Invalid duel result");
        }
        participants = List.copyOf(participants);
        if (participants.size() > 10000) throw new IllegalArgumentException("Too many duel participants");
        var ids = new HashSet<UUID>();
        for (Participant participant : participants) {
            if (!ids.add(participant.playerId())) throw new IllegalArgumentException("Duplicate duel participant");
        }
        // Stable ordering makes replay and readback comparisons independent of arena collection order.
        participants = participants.stream().sorted(Comparator.comparing(Participant::team)
                .thenComparing(participant -> participant.playerId().toString())).toList();
    }

    public record Participant(UUID playerId, String playerName, String team, int kills, double damage,
                              long participationSeconds, boolean eliminated) {
        public Participant {
            Objects.requireNonNull(playerId, "playerId");
            if (playerName == null || playerName.isBlank()) playerName = playerId.toString();
            if (playerName.length() > 64 || team == null || !Set.of("RED", "BLUE").contains(team)
                    || kills < 0 || !Double.isFinite(damage) || damage < 0 || participationSeconds < 0) {
                throw new IllegalArgumentException("Invalid duel participant");
            }
            if (damage == 0.0) damage = 0.0; // Canonicalize negative zero for database readback.
        }
    }
}
