package cloud.mineplay.mobarena.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import org.bukkit.configuration.ConfigurationSection;

public record EconomySettings(boolean enabled, BigDecimal baseWaveReward, boolean multiplyByWave,
        boolean playerScaling, BigDecimal additionalPlayerMultiplier, BigDecimal maximumMultiplier,
        BigDecimal maximumPot, BigDecimal maximumPlayerPayout, BigDecimal maximumDailyPayout,
        Duration rewardCooldown, int minimumRewardWave, long minimumParticipationSeconds, int scale,
        long shortSessionSeconds, BigDecimal shortSessionMultiplier, boolean auditLogging) {
    public EconomySettings {
        if (scale < 0 || scale > 2 || minimumRewardWave < 0 || minimumParticipationSeconds < 0
                || shortSessionSeconds < 0 || rewardCooldown.isNegative()) {
            throw new IllegalArgumentException("Invalid economy scale, time, or minimum wave");
        }
        for (BigDecimal value : new BigDecimal[]{baseWaveReward, additionalPlayerMultiplier, maximumMultiplier,
                maximumPot, maximumPlayerPayout, maximumDailyPayout, shortSessionMultiplier}) {
            if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("1000000000")) > 0) {
                throw new IllegalArgumentException("Economy values must be between 0 and 1,000,000,000");
            }
        }
        if (maximumMultiplier.compareTo(BigDecimal.ONE) < 0 || shortSessionMultiplier.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Invalid reward multiplier");
        }
        maximumPot = maximumPot.setScale(scale, RoundingMode.DOWN);
        maximumPlayerPayout = maximumPlayerPayout.setScale(scale, RoundingMode.DOWN);
        maximumDailyPayout = maximumDailyPayout.setScale(scale, RoundingMode.DOWN);
    }

    public static EconomySettings defaults() {
        return new EconomySettings(true, new BigDecimal("100"), true, true, new BigDecimal("0.25"),
                new BigDecimal("2"), new BigDecimal("50000"), new BigDecimal("25000"),
                new BigDecimal("50000"), Duration.ofMinutes(15), 3, 60, 2, 0, BigDecimal.ONE, true);
    }

    public static EconomySettings load(ConfigurationSection section) {
        if (section == null) throw new IllegalArgumentException("Missing economy configuration");
        fixed(section, "provider", "VAULT");
        fixed(section, "reward.mode", "KILL_SHARE");
        fixed(section, "distribution.zero-kills-policy", "NO_PAYOUT");
        String auditDatabase = section.getString("audit.database", "SQLITE");
        if (!"SQLITE".equalsIgnoreCase(auditDatabase) && !"MYSQL".equalsIgnoreCase(auditDatabase)) {
            throw new IllegalArgumentException("audit.database must be SQLITE or MYSQL; storage.type selects the actual backend");
        }
        for (String key : new String[]{"reward.only-completed-waves", "player-scaling.snapshot-on-start",
                "distribution.use-player-kills", "distribution.include-eliminated-players",
                "distribution.include-voluntary-leavers", "audit.persist-settlements"}) {
            if (!bool(section, key, true)) throw new IllegalArgumentException(key + " must be true for safe settlement");
        }
        return new EconomySettings(bool(section, "enabled", true), decimal(section, "reward.base-wave-reward", "100"),
                bool(section, "reward.multiply-by-wave", true), bool(section, "player-scaling.enabled", true),
                decimal(section, "player-scaling.additional-player-multiplier", "0.25"),
                decimal(section, "player-scaling.maximum-multiplier", "2"),
                decimal(section, "limits.maximum-pot-per-session", "50000"),
                decimal(section, "limits.maximum-player-payout-per-session", "25000"),
                decimal(section, "limits.maximum-daily-payout-per-player", "50000"),
                Duration.ofMinutes(integer(section, "limits.reward-cooldown-minutes", 15)),
                integer(section, "reward.minimum-reward-wave", 3),
                integer(section, "limits.minimum-participation-seconds", 60),
                integer(section, "distribution.rounding-scale", 2),
                integer(section, "short-session.threshold-seconds", 0),
                decimal(section, "short-session.multiplier", "1"),
                bool(section, "audit.enabled", true) && bool(section, "audit.log-payments", true));
    }

    private static BigDecimal decimal(ConfigurationSection s, String key, String fallback) {
        try { return new BigDecimal(String.valueOf(s.get(key, fallback))); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(key + " must be a finite decimal", exception); }
    }

    private static int integer(ConfigurationSection s, String key, int fallback) {
        try { return decimal(s, key, String.valueOf(fallback)).intValueExact(); }
        catch (ArithmeticException exception) { throw new IllegalArgumentException(key + " must be an integer", exception); }
    }

    private static boolean bool(ConfigurationSection s, String key, boolean fallback) {
        Object value = s.get(key, fallback);
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException(key + " must be true or false");
        return result;
    }

    private static void fixed(ConfigurationSection s, String key, String supported) {
        if (!supported.equalsIgnoreCase(String.valueOf(s.get(key, supported)))) {
            throw new IllegalArgumentException(key + " supports only " + supported);
        }
    }
}
