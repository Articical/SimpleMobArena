package cloud.mineplay.mobarena.messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArenaMessagesTest {
    @Test void prefixIsDistinctAndBodySeverityIsConsistent() {
        for (Component message : List.of(ArenaMessages.info("Notice"), ArenaMessages.success("Saved"),
                ArenaMessages.warning("Wait"), ArenaMessages.error("Failed"))) {
            Span prefix = spans(message).getFirst();
            assertEquals("MobArena", prefix.text());
            assertEquals(NamedTextColor.GOLD, prefix.colour());
            assertTrue(prefix.bold());
            assertEquals(new Span(" » ", NamedTextColor.DARK_GRAY, false), spans(message).get(1));
            assertFalse(spans(message).getLast().bold(), "Prefix bold must not leak into the body");
        }
        assertEquals(NamedTextColor.GRAY, spans(ArenaMessages.info("Notice")).getLast().colour());
        assertEquals(NamedTextColor.GREEN, spans(ArenaMessages.success("Saved")).getLast().colour());
        assertEquals(NamedTextColor.YELLOW, spans(ArenaMessages.warning("Wait")).getLast().colour());
        assertEquals(NamedTextColor.RED, spans(ArenaMessages.error("Failed")).getLast().colour());
    }

    @Test void slashCommandsAreHighlightedWithoutChangingTheUsageText() {
        String text = "Use /ma admin setup castle, then /mobarena join <arena> [personal|class].";
        Component message = ArenaMessages.info(text);
        assertEquals("MobArena » " + text, plain(message));
        assertEquals(List.of("/ma", "/mobarena"), spans(message).stream()
            .filter(span -> span.colour().equals(NamedTextColor.AQUA)).map(Span::text).toList());
        assertTrue(spans(message).stream().anyMatch(span -> span.text().contains("<arena>") && span.colour().equals(NamedTextColor.GRAY)));
    }

    @Test void playerTextCannotInjectFormattingOrClickableActions() {
        String text = "<red><click:run_command:'/op me'>Name</click></red> &aName §cName";
        Component message = ArenaMessages.warning(text);
        assertEquals("MobArena » " + text, plain(message));
        assertNoEvents(message);
        assertTrue(spans(message).stream().skip(2).allMatch(span -> span.colour().equals(NamedTextColor.YELLOW) || span.colour().equals(NamedTextColor.AQUA)));
    }

    @Test void pathsUrlsAndFractionsAreNotCommands() {
        Component message = ArenaMessages.info("Read arenas/castle.yml or https://example.test/help; players 2/4.");
        assertTrue(spans(message).stream().noneMatch(span -> span.colour().equals(NamedTextColor.AQUA)));
    }

    @Test void missingExceptionDetailsStillProduceAUsefulError() {
        assertEquals("MobArena » An unexpected error occurred. Check the server console.", plain(ArenaMessages.error(null)));
        assertEquals(ArenaMessages.error(null), ArenaMessages.error("   "));
    }

    @Test void explicitlyStyledAnnouncementsKeepTheirBodyStyle() {
        Component body = Component.text("Wave 3", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.text(" complete", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, false));
        Component message = ArenaMessages.info(body);
        assertEquals("MobArena » Wave 3 complete", plain(message));
        assertTrue(spans(message).contains(new Span("Wave 3", NamedTextColor.GOLD, true)));
        assertEquals(new Span(" complete", NamedTextColor.GREEN, false), spans(message).getLast());
    }

    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
    private static void assertNoEvents(Component component) {
        assertNull(component.clickEvent());
        assertNull(component.hoverEvent());
        component.children().forEach(ArenaMessagesTest::assertNoEvents);
    }
    private static List<Span> spans(Component component) {
        List<Span> result = new ArrayList<>();
        collect(component, null, false, result);
        return result;
    }
    private static void collect(Component component, TextColor inheritedColour, boolean inheritedBold, List<Span> result) {
        TextColor colour = component.color() == null ? inheritedColour : component.color();
        TextDecoration.State boldState = component.decoration(TextDecoration.BOLD);
        boolean bold = boldState == TextDecoration.State.NOT_SET ? inheritedBold : boldState == TextDecoration.State.TRUE;
        if (component instanceof TextComponent text && !text.content().isEmpty()) result.add(new Span(text.content(), colour, bold));
        for (Component child : component.children()) collect(child, colour, bold, result);
    }
    private record Span(String text, TextColor colour, boolean bold) {}
}
