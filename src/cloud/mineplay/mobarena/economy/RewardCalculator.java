package cloud.mineplay.mobarena.economy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure reward arithmetic; cap losses and eligibility losses are deliberately never redistributed. */
public final class RewardCalculator {
    private RewardCalculator() { }

    public static BigDecimal waveReward(EconomySettings settings, int wave, int startingPlayers) {
        if (wave < 1 || startingPlayers < 1) return BigDecimal.ZERO.setScale(settings.scale());
        BigDecimal multiplier = settings.playerScaling()
                ? BigDecimal.ONE.add(settings.additionalPlayerMultiplier().multiply(BigDecimal.valueOf(startingPlayers - 1)))
                    .min(settings.maximumMultiplier()) : BigDecimal.ONE;
        return settings.baseWaveReward().multiply(settings.multiplyByWave() ? BigDecimal.valueOf(wave) : BigDecimal.ONE)
                .multiply(multiplier).setScale(settings.scale(), RoundingMode.DOWN);
    }

    public static BigDecimal capPot(EconomySettings settings, BigDecimal pot) {
        return pot.max(BigDecimal.ZERO).min(settings.maximumPot()).setScale(settings.scale(), RoundingMode.DOWN);
    }

    public static Map<UUID, Allocation> distribute(SessionResult result, EconomySettings settings) {
        var allocations = new LinkedHashMap<UUID, Allocation>();
        List<SessionResult.Participant> participants = result.participants().stream()
                .sorted(Comparator.comparing(p -> p.playerId().toString())).toList();
        long totalKills = participants.stream().mapToLong(SessionResult.Participant::kills).sum();
        BigDecimal pot = capPot(settings, result.prizePot());
        String reductions = result.prizePot().compareTo(settings.maximumPot()) > 0 ? "SESSION_POT_CAP" : "";
        if (settings.shortSessionSeconds() > 0
                && Duration.between(result.startedAt(), result.finishedAt()).getSeconds() < settings.shortSessionSeconds()) {
            pot = pot.multiply(settings.shortSessionMultiplier()).setScale(settings.scale(), RoundingMode.DOWN);
            if (settings.shortSessionMultiplier().compareTo(BigDecimal.ONE) < 0) reductions = reductions.isEmpty() ? "SHORT_SESSION_REDUCTION" : reductions + ",SHORT_SESSION_REDUCTION";
        }
        String sessionReason = result.cancelled() ? "CANCELLED" : !settings.enabled() ? "PAYOUTS_DISABLED"
                : result.completedWaves() < settings.minimumRewardWave() ? "MINIMUM_WAVE"
                : totalKills == 0 ? "ZERO_KILLS" : "";
        // Compute every kill share before withholding players; their share is burned rather than shifted to others.
        BigInteger units = pot.movePointRight(settings.scale()).toBigIntegerExact();
        var shares = new ArrayList<Share>();
        BigInteger used = BigInteger.ZERO;
        for (var participant : participants) {
            BigInteger[] division = totalKills == 0 ? new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO}
                    : units.multiply(BigInteger.valueOf(participant.kills())).divideAndRemainder(BigInteger.valueOf(totalKills));
            shares.add(new Share(participant, division[0], division[1]));
            used = used.add(division[0]);
        }
        if (totalKills > 0) {
            shares.sort(Comparator.comparing(Share::remainder).reversed().thenComparing(s -> s.participant().playerId().toString()));
            int remainder = units.subtract(used).intValueExact();
            for (int i = 0; i < remainder; i++) {
                Share share = shares.get(i);
                shares.set(i, new Share(share.participant(), share.units().add(BigInteger.ONE), share.remainder()));
            }
        }
        shares.sort(Comparator.comparing(s -> s.participant().playerId().toString()));
        for (Share share : shares) {
            var participant = share.participant();
            BigDecimal gross = new BigDecimal(share.units(), settings.scale());
            BigDecimal fraction = totalKills == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(participant.kills()).divide(BigDecimal.valueOf(totalKills), 16, RoundingMode.DOWN);
            String reason = !sessionReason.isEmpty() ? sessionReason : participant.participationSeconds() < settings.minimumParticipationSeconds()
                    ? "MINIMUM_PARTICIPATION" : participant.kills() == 0 ? "NO_KILLS" : "";
            BigDecimal intended = reason.isEmpty() ? gross.min(settings.maximumPlayerPayout()) : BigDecimal.ZERO.setScale(settings.scale());
            if (intended.compareTo(gross) < 0 && reason.isEmpty()) reason = "PLAYER_SESSION_CAP";
            if (!reductions.isEmpty()) reason = reason.isEmpty() ? reductions : reason + "," + reductions;
            allocations.put(participant.playerId(), new Allocation(fraction, gross, intended, reason));
        }
        return allocations;
    }

    public record Allocation(BigDecimal share, BigDecimal gross, BigDecimal intended, String reason) { }
    private record Share(SessionResult.Participant participant, BigInteger units, BigInteger remainder) { }
}
