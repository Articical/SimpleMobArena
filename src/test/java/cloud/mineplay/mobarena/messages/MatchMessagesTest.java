package cloud.mineplay.mobarena.messages;

import cloud.mineplay.mobarena.economy.EconomySettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchMessagesTest {
    @Test void eliminationShowsPersonalContributionAndUnpaidEstimateWhileOthersContinue() {
        var result = summary("Castle", true, true, 125, new BigDecimal("123.459"));
        List<String> lines = MatchMessages.elimination(result).stream().map(MatchMessagesTest::plain).toList();

        assertEquals(4, lines.size());
        assertTrue(lines.get(0).contains("Eliminated in Castle"));
        assertTrue(lines.get(0).contains("7 kills · 182.5 damage · 2m 05s"));
        assertTrue(lines.get(1).contains("Wave reached: 5 · Waves completed: 4"));
        assertTrue(lines.get(2).contains("Estimated share: $123.45"));
        assertTrue(lines.get(2).contains("before eligibility/caps; not paid yet"));
        assertTrue(lines.get(3).contains("match continues"));
        assertTrue(lines.get(3).contains("/ma history"));
        assertEquals("ELIMINATED", plain(MatchMessages.eliminationTitle(result).title()));
        assertEquals(NamedTextColor.RED, MatchMessages.eliminationTitle(result).title().color());
    }

    @Test void voluntaryExitHasItsOwnTitleAndDoesNotClaimAPayment() {
        var result = summary("Castle", false, false, 3601, BigDecimal.ZERO);
        List<String> lines = MatchMessages.elimination(result).stream().map(MatchMessagesTest::plain).toList();

        assertTrue(lines.get(0).contains("Left Castle"));
        assertTrue(lines.get(0).contains("1h 00m 01s"));
        assertTrue(lines.get(2).contains("Estimated share: $0.00"));
        assertTrue(lines.get(2).contains("not paid yet"));
        assertTrue(lines.get(3).contains("Calculating your final reward"));
        assertFalse(lines.get(3).contains("match continues"));
        assertEquals("LEFT ARENA", plain(MatchMessages.eliminationTitle(result).title()));
        assertEquals(NamedTextColor.YELLOW, MatchMessages.eliminationTitle(result).title().color());
    }

    @Test void arenaNamesRemainLiteralInChatAndTitle() {
        String name = "<red><click:run_command:'/op me'>Castle</click></red>";
        var result = summary(name, true, true, 0, BigDecimal.ZERO);

        assertTrue(plain(MatchMessages.elimination(result).getFirst()).contains(name));
        assertTrue(plain(MatchMessages.eliminationTitle(result).subtitle()).startsWith(name));
        MatchMessages.elimination(result).forEach(MatchMessagesTest::assertNoEvents);
        assertNoEvents(MatchMessages.eliminationTitle(result).subtitle());
    }

    @Test void rewardRequirementsExplainTeamWavesPersonalTimeAndProvisionalPot() {
        var settings = EconomySettings.defaults();
        String joining = plain(MatchMessages.rewardRequirements(settings));
        assertTrue(joining.contains("3 completed waves as a team"));
        assertTrue(joining.contains("60s played and credited kills"));
        assertTrue(joining.contains("Paid after the match ends; cooldowns and caps apply"));
        String firstWave = plain(MatchMessages.rewardProgress(settings,1));
        assertTrue(firstWave.contains("1/3 completed"));
        assertTrue(firstWave.contains("pot is not payable yet"));
        String thirdWave = plain(MatchMessages.rewardProgress(settings,3));
        assertTrue(thirdWave.contains("Reward wave minimum met"));
        assertTrue(thirdWave.contains("60s participation minimum, kills, cooldown and caps still apply"));
    }

    private static MatchMessages.ExitSummary summary(String arena, boolean eliminated, boolean continues, long seconds, BigDecimal estimate) {
        return new MatchMessages.ExitSummary(arena, UUID.randomUUID(), eliminated ? "was eliminated" : "left the match",
            eliminated, 7, 182.46, 5, 4, seconds, estimate, continues);
    }
    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
    private static void assertNoEvents(Component component) {
        assertNull(component.clickEvent());
        assertNull(component.hoverEvent());
        component.children().forEach(MatchMessagesTest::assertNoEvents);
    }
}
