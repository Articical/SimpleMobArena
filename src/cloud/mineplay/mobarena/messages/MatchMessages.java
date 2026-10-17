package cloud.mineplay.mobarena.messages;

import cloud.mineplay.mobarena.economy.EconomySettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Immediate personal results, before the match's final eligibility and settlement checks. */
public final class MatchMessages {
    private MatchMessages() {}

    public static Component rewardRequirements(EconomySettings settings) {
        if (!settings.enabled()) return ArenaMessages.warning("Rewards are turned off for this match.");
        return ArenaMessages.info("To earn a reward, your team needs to finish " + waves(settings.minimumRewardWave()) + ". "
            + "You need " + settings.minimumParticipationSeconds() + "s played and at least one kill. "
            + "Rewards are paid after the match; cooldowns and limits apply.");
    }

    public static Component rewardProgress(EconomySettings settings, int completedWaves) {
        if (!settings.enabled()) return ArenaMessages.warning("Rewards are turned off for this match.");
        if (completedWaves < settings.minimumRewardWave()) {
            return ArenaMessages.warning("No rewards yet: " + completedWaves + "/" + settings.minimumRewardWave()
                + " waves cleared. You also need " + settings.minimumParticipationSeconds() + "s played and at least one kill.");
        }
        return ArenaMessages.info("Enough waves cleared for rewards. You still need " + settings.minimumParticipationSeconds()
            + "s played and at least one kill; cooldowns and limits apply.");
    }

    public record ExitSummary(String arenaName, UUID sessionId, String reason, boolean eliminated,
                              int kills, double damage, int waveReached, int completedWaves,
                              long seconds, BigDecimal estimatedGross, boolean matchContinues) {}

    public static List<Component> elimination(ExitSummary result) {
        NamedTextColor outcomeColour = result.eliminated() ? NamedTextColor.RED : NamedTextColor.YELLOW;
        return personalStats(result, result.eliminated() ? "Eliminated in " : "Left ", outcomeColour);
    }

    public static List<Component> completed(ExitSummary result) {
        return personalStats(result, "Finished ", NamedTextColor.GREEN);
    }

    private static List<Component> personalStats(ExitSummary result, String heading, NamedTextColor outcomeColour) {
        Component personal = Component.text(heading, outcomeColour, TextDecoration.BOLD)
            .append(Component.text(result.arenaName(), outcomeColour))
            .append(Component.text(" · ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
            .append(Component.text(result.kills() + " kills · " + damage(result.damage()) + " damage · " + duration(result.seconds()), NamedTextColor.GRAY)
                .decoration(TextDecoration.BOLD, false));
        Component progress = Component.text("Wave reached: ", NamedTextColor.GRAY)
            .append(Component.text(result.waveReached(), NamedTextColor.GOLD))
            .append(Component.text(" · Waves completed: ", NamedTextColor.GRAY))
            .append(Component.text(result.completedWaves(), NamedTextColor.GOLD));
        if (result.matchContinues()) {
            progress = progress.append(Component.text(" · Your reward is worked out when the match ends.", NamedTextColor.GRAY));
        }
        return List.of(ArenaMessages.info(personal), ArenaMessages.info(progress));
    }

    public static Title eliminationTitle(ExitSummary result) {
        Component title = Component.text(result.eliminated() ? "ELIMINATED" : "LEFT ARENA",
            result.eliminated() ? NamedTextColor.RED : NamedTextColor.YELLOW, TextDecoration.BOLD);
        Component subtitle = Component.text("left the arena boundary".equals(result.reason())
                ? "You left the arena boundary" : result.arenaName(), NamedTextColor.GRAY)
            .append(Component.text(" · " + result.kills() + " kills · wave " + result.waveReached(), NamedTextColor.AQUA));
        return Title.title(title, subtitle, Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500)));
    }

    private static String waves(int count) { return count + (count == 1 ? " wave" : " waves"); }
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
