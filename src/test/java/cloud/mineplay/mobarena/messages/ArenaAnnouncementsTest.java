package cloud.mineplay.mobarena.messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ArenaAnnouncementsTest {
    private JavaPlugin plugin;
    private Server server;
    private YamlConfiguration config;
    private Player joining;
    private Player outsider;

    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        server = mock(Server.class);
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        joining = player("Alex");
        outsider = player("Robin");
        doReturn(List.of(joining, outsider)).when(server).getOnlinePlayers();
    }

    @Test void defaultsBroadcastExactlyOnceAndOfferOnlyOutsidersASuggestCommand() {
        doReturn(List.of(joining, outsider, outsider)).when(server).getOnlinePlayers();
        new ArenaAnnouncements(plugin, "castle", "Castle Arena").joined(joining, 1, 4, List.of(joining));

        Component participantMessage = captured(joining);
        Component publicMessage = captured(outsider);
        assertTrue(plain(publicMessage).contains("Alex joined Castle Arena · 1/4 players"));
        assertTrue(clicks(participantMessage).isEmpty());
        assertEquals(List.of(ClickEvent.suggestCommand("/ma join castle")), clicks(publicMessage));
        assertEquals(1, hoverCount(publicMessage));
    }

    @Test void eachDisabledPublicEventFallsBackToParticipantsAndCachesSettings() {
        for (String event : List.of("join", "start", "end")) config.set("announcements.public." + event, false);
        ArenaAnnouncements announcements = new ArenaAnnouncements(plugin, "castle", "Castle Arena");
        // Reloading/reconstructing the arena is required to apply new settings mid-session.
        for (String event : List.of("join", "start", "end")) config.set("announcements.public." + event, true);
        List<Player> participants = List.of(joining, joining);

        announcements.joined(joining, 1, 4, participants);
        announcements.countdown(10, 1, 4, participants);
        announcements.countdownPaused(1, 2, participants);
        announcements.started(1, participants);
        announcements.finished(false, 3, participants);

        verify(joining, times(4)).sendMessage(any(Component.class));
        verify(joining, times(3)).showTitle(any(Title.class));
        verify(outsider, never()).sendMessage(any(Component.class));
        verify(outsider, never()).showTitle(any(Title.class));
        verify(server, never()).getOnlinePlayers();
    }

    @Test void fullArenaAndMissingJoinPermissionNeverOfferAnInvite() {
        ArenaAnnouncements announcements = new ArenaAnnouncements(plugin, "castle", "Castle Arena");
        announcements.joined(joining, 4, 4, List.of(joining));
        when(outsider.hasPermission("mobarena.join")).thenReturn(false);
        announcements.joined(joining, 1, 4, List.of(joining));

        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(outsider, times(2)).sendMessage(messages.capture());
        assertTrue(plain(messages.getAllValues().get(0)).contains("Arena full"));
        messages.getAllValues().forEach(message -> assertTrue(clicks(message).isEmpty()));
    }

    @Test void eventTextExplainsTimingClosedJoiningAndCompletionWithoutPromisingMoney() {
        ArenaAnnouncements announcements = new ArenaAnnouncements(plugin, "castle", "Castle Arena");
        announcements.countdown(15, 1, 4, List.of(joining));
        announcements.countdownPaused(1, 2, List.of(joining));
        announcements.started(2, List.of(joining));
        announcements.finished(false, 7, List.of(joining));
        announcements.finished(true, 2, List.of(joining));

        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(outsider, times(3)).sendMessage(messages.capture());
        List<String> lines = messages.getAllValues().stream().map(ArenaAnnouncementsTest::plain).toList();
        assertTrue(lines.get(0).contains("Joining is now closed"));
        assertTrue(lines.get(1).contains("7 completed waves"));
        assertTrue(lines.get(2).contains("cancelled after 2 completed waves"));
        assertTrue(lines.stream().noneMatch(line -> line.contains("paid") || line.contains("reward") || line.contains("$")));
        messages.getAllValues().forEach(message -> assertTrue(clicks(message).isEmpty()));
        verify(outsider, never()).showTitle(any(Title.class));
    }

    @Test void hostileLookingNamesRemainLiteralAndCannotAddRunCommandEvents() {
        String arenaName = "<red><click:run_command:'/op me'>Castle</click></red>";
        String playerName = "<green>Alex</green>";
        when(joining.getName()).thenReturn(playerName);
        new ArenaAnnouncements(plugin, "castle", arenaName).joined(joining, 1, 4, List.of(joining));

        Component output = captured(outsider);
        assertTrue(plain(output).contains(arenaName));
        assertTrue(plain(output).contains(playerName));
        assertEquals(List.of(ClickEvent.suggestCommand("/ma join castle")), clicks(output));
        assertTrue(clicks(output).stream().noneMatch(click -> click.action() == ClickEvent.Action.RUN_COMMAND));
    }

    @Test void oneRecipientFailureDoesNotPreventOtherPlayersFromReceivingTheEvent() {
        doThrow(new IllegalStateException("packet failed")).when(joining).sendMessage(any(Component.class));
        Logger logger = plugin.getLogger();
        doThrow(new IllegalStateException("logger failed")).when(logger).log(eq(Level.WARNING), anyString(), any(Throwable.class));
        ArenaAnnouncements announcements = new ArenaAnnouncements(plugin, "castle", "Castle Arena");

        assertDoesNotThrow(() -> announcements.started(2, List.of(joining)));

        assertTrue(plain(captured(outsider)).contains("has started with 2 players"));
        verify(joining).sendMessage(any(Component.class));
    }

    @Test void malformedBooleanWarnsAndUsesTheDocumentedPublicDefault() {
        config.set("announcements.public.join", "false");
        ArenaAnnouncements announcements = new ArenaAnnouncements(plugin, "castle", "Castle Arena");

        announcements.joined(joining, 1, 4, List.of(joining));

        verify(outsider).sendMessage(any(Component.class));
        verify(plugin.getLogger()).log(eq(Level.WARNING), argThat(message -> message.contains("announcements.public.join") && message.contains("using true")), isNull(Throwable.class));
    }

    @Test void publicAudienceFailureFallsBackToParticipantsEvenWhenLoggingAlsoFails() {
        when(server.getOnlinePlayers()).thenThrow(new IllegalStateException("server audience unavailable"));
        Logger logger = plugin.getLogger();
        doThrow(new IllegalStateException("logger failed")).when(logger).log(eq(Level.WARNING), anyString(), any(Throwable.class));
        ArenaAnnouncements announcements = new ArenaAnnouncements(plugin, "castle", "Castle Arena");

        assertDoesNotThrow(() -> announcements.joined(joining, 1, 4, List.of(joining, joining)));

        assertTrue(plain(captured(joining)).contains("Alex joined Castle Arena"));
        verify(outsider, never()).sendMessage(any(Component.class));
    }

    @Test void recipientsWhoHaveDisconnectedAreSkipped() {
        when(outsider.isOnline()).thenReturn(false);
        new ArenaAnnouncements(plugin, "castle", "Castle Arena").started(1, List.of(joining));
        verify(joining).sendMessage(any(Component.class));
        verify(outsider, never()).sendMessage(any(Component.class));
    }

    @Test void legacyPublicCountdownTrueStillShowsOnlyPrivateTitlesToUniqueOnlineParticipants() {
        config.set("announcements.public.countdown", true);
        Player offline = player("Offline");
        when(offline.isOnline()).thenReturn(false);
        ArenaAnnouncements announcements = new ArenaAnnouncements(plugin, "castle", "Castle Arena");

        announcements.countdown(10, 1, 4, List.of(joining, joining, offline));

        Title title = capturedTitle(joining);
        assertEquals("STARTING IN 10s", plain(title.title()));
        assertEquals(NamedTextColor.GOLD, title.title().color());
        assertEquals("Castle Arena · 1/4 players", plain(title.subtitle()));
        assertEquals(Duration.ofMillis(1100), title.times().stay());
        verify(joining, never()).sendMessage(any(Component.class));
        verify(offline, never()).showTitle(any(Title.class));
        verify(outsider, never()).showTitle(any(Title.class));
        verify(outsider, never()).sendMessage(any(Component.class));
        verify(server, never()).getOnlinePlayers();
    }

    @Test void pausedCountdownHasPrivateTitleAndExplanationWithoutPublicInvites() {
        config.set("announcements.public.countdown", true);
        ArenaAnnouncements announcements = new ArenaAnnouncements(plugin, "castle", "Castle Arena");

        announcements.countdownPaused(1, 2, List.of(joining, joining));

        assertEquals("COUNTDOWN PAUSED", plain(capturedTitle(joining).title()));
        Component explanation = captured(joining);
        assertTrue(plain(explanation).contains("1/2 players needed to start"));
        assertTrue(clicks(explanation).isEmpty());
        verify(outsider, never()).showTitle(any(Title.class));
        verify(outsider, never()).sendMessage(any(Component.class));
        verify(server, never()).getOnlinePlayers();
    }

    @Test void startShowsParticipantsFightTitleAndPreservesPublicStartChat() {
        new ArenaAnnouncements(plugin, "castle", "Castle Arena").started(1, List.of(joining, joining));

        Title title = capturedTitle(joining);
        assertEquals("FIGHT!", plain(title.title()));
        assertEquals(NamedTextColor.GREEN, title.title().color());
        assertTrue(plain(captured(outsider)).contains("Joining is now closed"));
        verify(outsider, never()).showTitle(any(Title.class));
    }

    @Test void cancellationClearsOwnedCountdownBeforeChatAndLobbyLeaveClearsOnlyOnce() {
        config.set("announcements.public.end", false);
        ArenaAnnouncements announcements = new ArenaAnnouncements(plugin, "castle", "Castle Arena");
        announcements.countdown(5, 1, 4, List.of(joining));

        announcements.finished(true, 0, List.of(joining, joining));
        announcements.clearCountdown(joining);

        var order = inOrder(joining);
        order.verify(joining).showTitle(any(Title.class));
        order.verify(joining).clearTitle();
        order.verify(joining).sendMessage(any(Component.class));
        verify(joining, times(1)).clearTitle();
        verify(outsider, never()).clearTitle();

        announcements.countdown(3, 1, 4, List.of(joining));
        announcements.clearCountdown(joining);
        verify(joining, times(2)).clearTitle();
    }

    @Test void startingReleasesCountdownTitleOwnershipSoFinishCannotEraseEliminationTitle() {
        ArenaAnnouncements announcements = new ArenaAnnouncements(plugin, "castle", "Castle Arena");
        announcements.countdown(1, 1, 4, List.of(joining));
        announcements.started(1, List.of(joining));
        joining.showTitle(Title.title(Component.text("ELIMINATED"), Component.empty()));

        announcements.finished(false, 1, List.of(joining));
        announcements.clearCountdown(joining);

        verify(joining, never()).clearTitle();
    }

    @Test void titleClearAndLoggingFailuresCannotBlockOtherParticipantsOrFinishChat() {
        config.set("announcements.public.end", false);
        Player teammate = player("Sam");
        doThrow(new IllegalStateException("title packet failed")).when(joining).showTitle(any(Title.class));
        doThrow(new IllegalStateException("clear packet failed")).when(joining).clearTitle();
        Logger logger = plugin.getLogger();
        doThrow(new IllegalStateException("logger failed")).when(logger)
            .log(eq(Level.WARNING), anyString(), any(Throwable.class));
        ArenaAnnouncements announcements = new ArenaAnnouncements(plugin, "castle", "Castle Arena");
        List<Player> participants = List.of(joining, teammate);

        assertDoesNotThrow(() -> announcements.countdown(5, 2, 4, participants));
        assertDoesNotThrow(() -> announcements.finished(true, 0, participants));

        verify(teammate).showTitle(any(Title.class));
        verify(teammate).clearTitle();
        assertTrue(plain(captured(teammate)).contains("cancelled after 0 completed waves"));
        verify(joining).sendMessage(any(Component.class));
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("mobarena.join")).thenReturn(true);
        return player;
    }
    private static Component captured(Player player) {
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(message.capture());
        return message.getValue();
    }
    private static Title capturedTitle(Player player) {
        ArgumentCaptor<Title> title = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(title.capture());
        return title.getValue();
    }
    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
    private static List<ClickEvent> clicks(Component component) {
        List<ClickEvent> events = new ArrayList<>();
        if (component.clickEvent() != null) events.add(component.clickEvent());
        for (Component child : component.children()) events.addAll(clicks(child));
        return events;
    }
    private static int hoverCount(Component component) {
        return (component.hoverEvent() == null ? 0 : 1) + component.children().stream().mapToInt(ArenaAnnouncementsTest::hoverCount).sum();
    }
}
