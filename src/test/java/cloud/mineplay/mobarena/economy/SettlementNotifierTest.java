package cloud.mineplay.mobarena.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettlementNotifierTest {
    private final UUID playerId = UUID.randomUUID(), sessionId = UUID.randomUUID(), settlementId = UUID.randomUUID();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private JavaPlugin plugin;
    private Server server;
    private Player player;
    private SettlementNotifier notices;

    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        server = mock(Server.class);
        player = mock(Player.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("settlement-notifier-test"));
        when(plugin.isEnabled()).thenReturn(true);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(player.isOnline()).thenReturn(true);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            scheduled.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        notices = new SettlementNotifier(plugin, closing::get);
    }

    @Test void newerDurableConfirmationReplacesQueuedPendingAndBlocksLaterStalePending() {
        notices.publish("roof", payment(Settlement.Status.PENDING, ""));
        notices.publish("roof", payment(Settlement.Status.CONFIRMED, ""));
        notices.publish("roof", payment(Settlement.Status.PENDING, ""));
        assertEquals(2, scheduled.size(), "Stale pending cannot replace a queued confirmation");
        verify(server, never()).getPlayer(any(UUID.class));
        scheduled.get(1).run();
        scheduled.getFirst().run();
        notices.publish("roof", payment(Settlement.Status.PENDING, ""));
        scheduled.getLast().run();

        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(player, times(1)).sendMessage(message.capture());
        assertTrue(plain(message.getValue()).contains("Paid $42.50"));
    }

    @Test void offlineDisabledAndClosingRecipientsDoNotReceiveMessages() {
        notices.publish("roof", payment(Settlement.Status.CONFIRMED, ""));
        when(player.isOnline()).thenReturn(false);
        scheduled.getLast().run();
        when(player.isOnline()).thenReturn(true);

        notices.publish("roof", payment(Settlement.Status.CONFIRMED, ""));
        when(plugin.isEnabled()).thenReturn(false);
        scheduled.getLast().run();
        int count = scheduled.size();
        notices.publish("roof", payment(Settlement.Status.CONFIRMED, ""));
        assertEquals(count, scheduled.size());

        when(plugin.isEnabled()).thenReturn(true);
        notices.publish("roof", payment(Settlement.Status.CONFIRMED, ""));
        closing.set(true);
        scheduled.getLast().run();
        count = scheduled.size();
        notices.publish("roof", payment(Settlement.Status.CONFIRMED, ""));
        assertEquals(count, scheduled.size());
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test void playerSendFailureCannotEscapeTheScheduledTask() {
        doThrow(new IllegalStateException("connection closed")).when(player).sendMessage(any(Component.class));
        notices.publish("roof", payment(Settlement.Status.CONFIRMED, ""));

        assertDoesNotThrow(() -> scheduled.getLast().run());
    }

    @Test void settlementStatusesUseHonestAmountsAndHumanEligibilityReasons() {
        String withheld = plain(SettlementNotifier.message("roof", payment(Settlement.Status.WITHHELD,
                "MINIMUM_PARTICIPATION,DAILY_CAP,REWARD_COOLDOWN")));
        assertTrue(withheld.contains("Reward $0; no payment"));
        assertTrue(withheld.contains("minimum participation time was not reached"));
        assertTrue(withheld.contains("your daily reward limit applied"));
        assertTrue(withheld.contains("your reward cooldown was active"));
        assertFalse(withheld.contains("DAILY_CAP"));
        for (Settlement.Status status : List.of(Settlement.Status.PENDING, Settlement.Status.PROCESSING)) {
            String text = plain(SettlementNotifier.message("roof", payment(status, "PLAYER_SESSION_CAP")));
            assertTrue(text.contains("Reward $42.50 awaiting payment"));
            assertTrue(text.contains("your per-match reward limit applied"));
            assertFalse(text.contains("Paid "));
        }
        for (Settlement.Status status : List.of(Settlement.Status.FAILED, Settlement.Status.UNCERTAIN)) {
            String text = plain(SettlementNotifier.message("roof", payment(status, "")));
            assertTrue(text.contains("administrator"));
            assertFalse(text.contains("Paid "));
            assertTrue(text.contains("/ma history"));
        }
    }

    @Test void withheldMinimumsUseTheActualSettlementPolicy() {
        EconomySettings policy = RewardCalculatorTest.policy("1000", "1000", "1000", 0, 5, 90, 0, "1");
        assertTrue(plain(SettlementNotifier.message("roof", payment(Settlement.Status.WITHHELD, "MINIMUM_WAVE"), policy))
                .contains("at least 5 completed waves are required"));
        assertTrue(plain(SettlementNotifier.message("roof", payment(Settlement.Status.WITHHELD, "MINIMUM_PARTICIPATION"), policy))
                .contains("at least 90 seconds of participation are required"));
    }

    private Settlement payment(Settlement.Status status, String caps) {
        return new Settlement(settlementId, sessionId, playerId, BigDecimal.ONE, new BigDecimal("50.00"),
                new BigDecimal("42.50"), caps, status, status == Settlement.Status.CONFIRMED ? Instant.now() : null, null);
    }
    private static String plain(Component value) { return PlainTextComponentSerializer.plainText().serialize(value); }
}
