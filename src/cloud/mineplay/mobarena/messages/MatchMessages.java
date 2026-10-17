package cloud.mineplay.mobarena.messages;

import cloud.mineplay.mobarena.economy.EconomySettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Immediate personal results, before the match's final eligibility and settlement checks. */
public final class MatchMessages {
    private MatchMessages() {}

    public static Component rewardRequirements(EconomySettings settings) {
        if (!settings.enabled()) return ArenaMessages.warning("Rewards are currently disabled for this match.");
        return ArenaMessages.info("Rewards require " + settings.minimumRewardWave() + " completed waves as a team, "
            + settings.minimumParticipationSeconds() + "s played and credited kills. Paid after the match ends; cooldowns and caps apply.");
    }

    public static Component rewardProgress(EconomySettings settings, int completedWaves) {
        if (!settings.enabled()) return ArenaMessages.warning("Rewards are currently disabled for this match.");
        if (completedWaves < settings.minimumRewardWave()) {
            return ArenaMessages.warning("Reward waves: " + completedWaves + "/" + settings.minimumRewardWave()
                + " completed. The pot is not payable yet; you also need " + settings.minimumParticipationSeconds() + "s played and credited kills.");
        }
        return ArenaMessages.info("Reward wave minimum met. Your " + settings.minimumParticipationSeconds()
            + "s participation minimum, kills, cooldown and caps still apply.");
    }

    public record ExitSummary(String arenaName, UUID sessionId, String reason, boolean eliminated,
                              int kills, double damage, int waveReached, int completedWaves,
                              long seconds, BigDecimal estimatedGross, boolean matchContinues) {}

    public static List<Component> elimination(ExitSummary result) {
        NamedTextColor outcomeColour = result.eliminated() ? NamedTextColor.RED : NamedTextColor.YELLOW;
        Component personal = Component.text(result.eliminated() ? "Eliminated in " : "Left ", outcomeColour, TextDecoration.BOLD)
            .append(Component.text(result.arenaName(), outcomeColour))
            .append(Component.text(" · ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
            .append(Component.text(result.kills() + " kills · " + damage(result.damage()) + " damage · " + duration(result.seconds()), NamedTextColor.GRAY)
                .decoration(TextDecoration.BOLD, false));
        Component progress = Component.text("Wave reached: ", NamedTextColor.GRAY)
            .append(Component.text(result.waveReached(), NamedTextColor.GOLD))
            .append(Component.text(" · Waves completed: ", NamedTextColor.GRAY))
            .append(Component.text(result.completedWaves(), NamedTextColor.GOLD));
        Component estimate = Component.text("Estimated share: ", NamedTextColor.GRAY)
            .append(Component.text(money(result.estimatedGross()), NamedTextColor.YELLOW, TextDecoration.BOLD))
            .append(Component.text(" · before eligibility/caps; not paid yet", NamedTextColor.GRAY));
        String pending = result.matchContinues()
            ? "The match continues. Your final reward is calculated when it ends; check /ma history."
            : "Calculating your final reward. Your payout result will follow; check /ma history.";
        return List.of(ArenaMessages.info(personal), ArenaMessages.info(progress),
            ArenaMessages.info(estimate), ArenaMessages.info(pending));
    }

    public static Title eliminationTitle(ExitSummary result) {
        Component title = Component.text(result.eliminated() ? "ELIMINATED" : "LEFT ARENA",
            result.eliminated() ? NamedTextColor.RED : NamedTextColor.YELLOW, TextDecoration.BOLD);
        Component subtitle = Component.text("left the arena boundary".equals(result.reason())
                ? "You left the arena boundary" : result.arenaName(), NamedTextColor.GRAY)
            .append(Component.text(" · " + result.kills() + " kills · wave " + result.waveReached(), NamedTextColor.AQUA));
        return Title.title(title, subtitle, Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500)));
    }

    private static String money(BigDecimal value) { return "$" + value.setScale(2, RoundingMode.DOWN).toPlainString(); }
    private static String damage(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static String duration(long seconds) {
        long total = Math.max(0, seconds);
        long hours = total / 3600;
        long minutes = total % 3600 / 60;
        long remainder = total % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, remainder)
            : String.format(Locale.ROOT, "%dm %02ds", minutes, remainder);
    }
}
