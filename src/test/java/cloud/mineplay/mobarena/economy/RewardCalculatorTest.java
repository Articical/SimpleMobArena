package cloud.mineplay.mobarena.economy;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RewardCalculatorTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);
    private static final Instant START = Instant.parse("2026-10-16T12:00:00Z");

    @Test void completedWavesAccumulateWithoutPartialWaveReward() {
        var settings = EconomySettings.defaults();
        BigDecimal pot = BigDecimal.ZERO;
        for (int wave = 1; wave <= 30; wave++) pot = pot.add(RewardCalculator.waveReward(settings, wave, 1));
        assertMoney("46500", pot);
        assertMoney("0", RewardCalculator.waveReward(settings, 0, 1));
        assertMoney("0", RewardCalculator.waveReward(settings, 1, 0));
    }

    @Test void startingPlayerMultiplierIsBounded() {
        var settings = EconomySettings.defaults();
        assertMoney("100", RewardCalculator.waveReward(settings, 1, 1));
        assertMoney("175", RewardCalculator.waveReward(settings, 1, 4));
        assertMoney("200", RewardCalculator.waveReward(settings, 1, 100));
    }

    @Test void potAndPlayerCapsAreAppliedWithoutRedistributingBurnedMoney() {
        var settings = policy("100", "20", "1000", 0, 0, 0, 0, "1");
        var allocations = RewardCalculator.distribute(result("1000", p(A, 3, 120), p(B, 1, 120)), settings);
        assertMoney("75", allocations.get(A).gross());
        assertMoney("20", allocations.get(A).intended());
        assertMoney("20", allocations.get(B).intended());
    }

    @Test void killSharesAreProportional() {
        var allocations = RewardCalculator.distribute(result("100", p(A, 50, 120), p(B, 25, 120), p(C, 25, 120)), EconomySettings.defaults());
        assertMoney("50", allocations.get(A).intended());
        assertMoney("25", allocations.get(B).intended());
        assertMoney("25", allocations.get(C).intended());
    }

    @Test void largestRemainderIsDeterministicAndIndependentOfParticipantOrder() {
        var settings = EconomySettings.defaults();
        var first = RewardCalculator.distribute(result("0.02", p(C, 1, 120), p(A, 1, 120), p(B, 1, 120)), settings);
        var reversed = RewardCalculator.distribute(result("0.02", p(B, 1, 120), p(C, 1, 120), p(A, 1, 120)), settings);
        assertEquals(first, reversed);
        assertMoney("0.01", first.get(A).intended());
        assertMoney("0.01", first.get(B).intended());
        assertMoney("0", first.get(C).intended());
    }

    @Test void zeroKillsLeavesPotUnallocated() {
        var allocations = RewardCalculator.distribute(result("100", p(A, 0, 120), p(B, 0, 120)), EconomySettings.defaults());
        assertTrue(allocations.values().stream().allMatch(a -> a.intended().signum() == 0 && a.reason().equals("ZERO_KILLS")));
    }

    @Test void minimumParticipationWithholdsEarnedShareWithoutBoostingOtherPlayers() {
        var allocations = RewardCalculator.distribute(result("100", p(A, 1, 59), p(B, 1, 120)), EconomySettings.defaults());
        assertMoney("0", allocations.get(A).intended());
        assertMoney("50", allocations.get(B).intended());
        assertEquals("MINIMUM_PARTICIPATION", allocations.get(A).reason());
    }

    @Test void minimumWaveAndCancellationWithholdEverything() {
        var result = new SessionResult(UUID.randomUUID(), "spawn", START, START.plusSeconds(120), 2,
                new BigDecimal("100"), List.of(p(A, 10, 120)), false);
        assertEquals("MINIMUM_WAVE", RewardCalculator.distribute(result, EconomySettings.defaults()).get(A).reason());
        var cancelled = new SessionResult(UUID.randomUUID(), "spawn", START, START.plusSeconds(120), 10,
                new BigDecimal("100"), List.of(p(A, 10, 120)), true);
        assertEquals("CANCELLED", RewardCalculator.distribute(cancelled, EconomySettings.defaults()).get(A).reason());
        assertMoney("0", RewardCalculator.distribute(cancelled, EconomySettings.defaults()).get(A).intended());
    }

    @Test void shortSessionReductionAndFractionalPotRoundDown() {
        var allocations = RewardCalculator.distribute(result("100.019", p(A, 1, 120)), policy("1000", "1000", "1000", 0, 0, 0, 180, "0.5"));
        assertMoney("50", allocations.get(A).intended());
    }

    @Test void configurationRejectsUnsafeAndMistypedValues() {
        var yaml = new YamlConfiguration();
        yaml.set("enabled", true);
        assertTrue(EconomySettings.load(yaml).enabled());
        yaml.set("reward.base-wave-reward", "NaN");
        assertThrows(IllegalArgumentException.class, () -> EconomySettings.load(yaml));
        yaml.set("reward.base-wave-reward", 100);
        yaml.set("distribution.rounding-scale", 3);
        assertThrows(IllegalArgumentException.class, () -> EconomySettings.load(yaml));
        yaml.set("distribution.rounding-scale", 2);
        yaml.set("limits.reward-cooldown-minutes", -1);
        assertThrows(IllegalArgumentException.class, () -> EconomySettings.load(yaml));
        yaml.set("limits.reward-cooldown-minutes", 15);
        yaml.set("reward.only-completed-waves", false);
        assertThrows(IllegalArgumentException.class, () -> EconomySettings.load(yaml));
        yaml.set("reward.only-completed-waves", true);
        yaml.set("enabled", "false");
        assertThrows(IllegalArgumentException.class, () -> EconomySettings.load(yaml));
    }

    @Test void duplicateParticipantsAreRejectedBeforeSettlement() {
        assertThrows(IllegalArgumentException.class, () -> result("100", p(A, 1, 120), p(A, 2, 120)));
    }

    static EconomySettings policy(String pot, String player, String daily, long cooldownMinutes, int wave,
            long participation, long shortSeconds, String shortMultiplier) {
        return new EconomySettings(true, new BigDecimal("100"), true, true, new BigDecimal("0.25"), new BigDecimal("2"),
                new BigDecimal(pot), new BigDecimal(player), new BigDecimal(daily), Duration.ofMinutes(cooldownMinutes),
                wave, participation, 2, shortSeconds, new BigDecimal(shortMultiplier), true);
    }

    private static SessionResult result(String pot, SessionResult.Participant... participants) {
        return new SessionResult(UUID.randomUUID(), "spawn", START, START.plusSeconds(120), 3, new BigDecimal(pot), List.of(participants), false);
    }

    private static SessionResult.Participant p(UUID id, int kills, long seconds) {
        return new SessionResult.Participant(id, kills, kills * 20.0, seconds);
    }

    static void assertMoney(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual), actual.toPlainString()); }
}
