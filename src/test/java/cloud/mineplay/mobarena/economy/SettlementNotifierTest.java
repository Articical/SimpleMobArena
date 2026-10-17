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
        assertTrue(withheld.contains("No reward this match"));
        assertTrue(withheld.contains("you didn't play long enough"));
        assertTrue(withheld.contains("you reached your daily reward limit"));
        assertTrue(withheld.contains("your reward cooldown hadn't ended"));
        assertFalse(withheld.contains("DAILY_CAP"));
        for (Settlement.Status status : List.of(Settlement.Status.PENDING, Settlement.Status.PROCESSING)) {
            String text = plain(SettlementNotifier.message("roof", payment(status, "PLAYER_SESSION_CAP")));
            assertTrue(text.contains("Your reward: $42.50. Payment queued"));
            assertTrue(text.contains("you reached the reward limit for one match"));
            assertFalse(text.contains("Paid "));
        }
        for (Settlement.Status status : List.of(Settlement.Status.FAILED, Settlement.Status.UNCERTAIN)) {
            String text = plain(SettlementNotifier.message("roof", payment(status, "")));
            assertTrue(text.contains("admin"));
            assertFalse(text.contains("Paid "));
            assertTrue(text.contains("/ma history"));
        }
    }

    @Test void withheldMinimumsUseTheActualSettlementPolicy() {
        EconomySettings policy = RewardCalculatorTest.policy("1000", "1000", "1000", 0, 5, 90, 0, "1");
        assertTrue(plain(SettlementNotifier.message("roof", payment(Settlement.Status.WITHHELD, "MINIMUM_WAVE"), policy))
                .contains("your team needed to finish 5 waves"));
        assertTrue(plain(SettlementNotifier.message("roof", payment(Settlement.Status.WITHHELD, "MINIMUM_PARTICIPATION"), policy))
                .contains("you needed to play for at least 90 seconds"));
    }

    @Test void confirmedReceiptExplainsOnlyAnActualCapRecheckReduction() {
        String reduced = plain(SettlementNotifier.message("roof", payment(Settlement.Status.CONFIRMED,
                "PLAYER_SESSION_CAP, CAP_RECHECK_AT_PAYMENT")));
        assertTrue(reduced.contains("Paid $42.50. Your reward limit changed before payment."));
        for (String reasons : List.of("", "PLAYER_SESSION_CAP", "NOT_CAP_RECHECK_AT_PAYMENT")) {
            String normal = plain(SettlementNotifier.message("roof", payment(Settlement.Status.CONFIRMED, reasons)));
            assertEquals("MobArena » roof: Paid $42.50.", normal);
        }
    }

    private Settlement payment(Settlement.Status status, String caps) {
        return new Settlement(settlementId, sessionId, playerId, BigDecimal.ONE, new BigDecimal("50.00"),
                new BigDecimal("42.50"), caps, status, status == Settlement.Status.CONFIRMED ? Instant.now() : null, null);
    }
    private static String plain(Component value) { return PlainTextComponentSerializer.plainText().serialize(value); }
}
