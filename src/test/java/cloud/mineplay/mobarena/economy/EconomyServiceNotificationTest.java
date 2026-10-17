package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.storage.SettlementStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EconomyServiceNotificationTest {
    @TempDir Path directory;
    private final UUID playerId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID settlementId = UUID.randomUUID();
    private final Instant started = Instant.parse("2026-10-17T12:00:00Z");
    private final LinkedBlockingQueue<Runnable> scheduled = new LinkedBlockingQueue<>();
    private final List<Runnable> timers = new ArrayList<>();
    private final AtomicReference<Settlement> recorded = new AtomicReference<>();
    private JavaPlugin plugin;
    private Server server;
    private BukkitScheduler scheduler;
    private SettlementStore store;
    private VaultGateway gateway;
    private Player player;
    private EconomyService service;

    @BeforeEach void setup() throws Exception {
        plugin = mock(JavaPlugin.class);
        server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        store = mock(SettlementStore.class);
        gateway = mock(VaultGateway.class);
        player = mock(Player.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reward-notification-test"));
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.isEnabled()).thenReturn(true);
        YamlConfiguration config = new YamlConfiguration();
        config.createSection("economy");
        config.set("economy.payment-delay-seconds", 0);
        when(plugin.getConfig()).thenReturn(config);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(player.isOnline()).thenReturn(true);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
            timers.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            scheduled.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        recorded.set(payment(Settlement.Status.PENDING, ""));
        when(store.payouts(sessionId)).thenAnswer(call -> List.of(recorded.get()));
        when(store.get(settlementId)).thenAnswer(call -> recorded.get());
        when(store.match(sessionId)).thenReturn(new MatchDetails(sessionId, "roof", started, started.plusSeconds(120),
                3, 10, new BigDecimal("100.00"), BigDecimal.ZERO, false, "COMPLETED", List.of()));
        when(store.pending(25)).thenAnswer(call -> recorded.get().status() == Settlement.Status.PENDING ? List.of(recorded.get()) : List.of());
        when(store.claim(eq(settlementId), any(), any())).thenAnswer(call -> {
            recorded.set(payment(Settlement.Status.PROCESSING, ""));
            return recorded.get();
        });
        doAnswer(call -> {
            recorded.set(payment(call.getArgument(1), ""));
            return null;
        }).when(store).complete(eq(settlementId), any(), any(), any());
        when(gateway.available(anyInt())).thenReturn(true);
        when(gateway.deposit(eq(playerId), any(), anyInt())).thenReturn(new PaymentOutcome(Settlement.Status.CONFIRMED, ""));
        service = new EconomyService(plugin, gateway, settings -> store);
        flushWorker();
    }

    @AfterEach void cleanup() { if (service != null) service.close(); }

    @Test void finalAllocationIsScheduledAfterPrepareAndPaidIsSentOnlyAfterCompletionCommits() throws Exception {
        service.finish(result()).get(5, TimeUnit.SECONDS);
        verify(store).prepare(any(), any(), any());
        verify(server, never()).getPlayer(any(UUID.class));
        verify(player, never()).sendMessage(any(Component.class));
        nextTask().run();
        assertTrue(messages().getFirst().contains("Your reward: $100.00. Payment queued"));

        timers.getFirst().run();
        nextTask().run(); // Main-thread Vault call; no player access on the worker.
        flushWorker();
        verify(store).complete(eq(settlementId), eq(Settlement.Status.CONFIRMED), eq(""), any());
        assertEquals(1, messages().size(), "Completion alone must not send from the ledger thread");
        nextTask().run();

        assertEquals(2, messages().size());
        assertTrue(messages().getLast().contains("roof: Paid $100.00."));
        assertFalse(messages().getLast().contains("/ma history"));
        verify(gateway, times(1)).deposit(eq(playerId), eq(new BigDecimal("100.00")), anyInt());
    }

    @Test void failedPrepareDoesNotAnnounceAnAllocation() throws Exception {
        when(store.prepare(any(), any(), any())).thenThrow(new SQLException("prepare failed"));

        assertThrows(Exception.class, () -> service.finish(result()).get(5, TimeUnit.SECONDS));

        assertTrue(scheduled.isEmpty());
        verify(store, never()).payouts(any());
        verify(player, never()).sendMessage(any(Component.class));
        verify(gateway, never()).deposit(any(), any(), anyInt());
    }

    @Test void successfulProviderResponseWithFailedLedgerCompletionNeverClaimsPaymentWasConfirmed() throws Exception {
        service.finish(result()).get(5, TimeUnit.SECONDS);
        nextTask().run();
        doThrow(new SQLException("commit failed")).when(store).complete(eq(settlementId), any(), any(), any());

        timers.getFirst().run();
        nextTask().run();
        flushWorker();

        assertTrue(scheduled.isEmpty());
        assertEquals(Settlement.Status.PROCESSING, recorded.get().status());
        assertEquals(1, messages().size());
        assertFalse(messages().getFirst().contains("Paid "));
        assertTrue(service.status().contains("ledger error"));
    }

    @Test void notificationReadAndSchedulingFailuresDoNotFailDurableFinishOrDisablePayments() throws Exception {
        when(store.payouts(sessionId)).thenThrow(new SQLException("notice read failed"));
        service.finish(result()).get(5, TimeUnit.SECONDS);
        assertFalse(service.status().contains("ledger error"));

        doReturn(List.of(recorded.get())).when(store).payouts(sessionId);
        doThrow(new IllegalStateException("scheduler stopped")).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        service.finish(result()).get(5, TimeUnit.SECONDS);

        assertFalse(service.status().contains("ledger error"));
        verify(gateway, never()).deposit(any(), any(), anyInt());
    }

    @Test void zeroAtClaimCapRecheckNotifiesWithheldWithoutAttemptingDeposit() throws Exception {
        service.finish(result()).get(5, TimeUnit.SECONDS);
        nextTask().run();
        doAnswer(call -> {
            recorded.set(new Settlement(settlementId, sessionId, playerId, BigDecimal.ONE, new BigDecimal("100.00"),
                    new BigDecimal("0.00"), "CAP_RECHECK_AT_PAYMENT", Settlement.Status.WITHHELD, null, null));
            return null;
        }).when(store).claim(eq(settlementId), any(), any());

        timers.getFirst().run();
        flushWorker();
        nextTask().run();

        assertTrue(messages().getLast().contains("No reward this match: your reward limit changed before payment"));
        verify(gateway, never()).deposit(any(), any(), anyInt());
    }

    @Test void receiptReadFailureAfterCommitCannotFailPaymentOrDeliverAnOldPendingNotice() throws Exception {
        service.finish(result()).get(5, TimeUnit.SECONDS);
        Runnable olderPendingNotice = nextTask();
        when(store.match(sessionId)).thenThrow(new SQLException("receipt metadata unavailable"));

        timers.getFirst().run();
        nextTask().run();
        flushWorker();
        olderPendingNotice.run();

        assertEquals(Settlement.Status.CONFIRMED, recorded.get().status());
        assertFalse(service.status().contains("ledger error"));
        assertTrue(scheduled.isEmpty());
        verify(player, never()).sendMessage(any(Component.class));
        verify(gateway, times(1)).deposit(eq(playerId), any(), anyInt());
    }

    private void flushWorker() throws Exception { service.stats(playerId).get(5, TimeUnit.SECONDS); }
    private Runnable nextTask() throws InterruptedException {
        Runnable task = scheduled.poll(5, TimeUnit.SECONDS);
        assertNotNull(task, "Expected a main-thread task");
        return task;
    }
    private SessionResult result() {
        return new SessionResult(sessionId, "roof", started, started.plusSeconds(120), 3, new BigDecimal("100.00"),
                List.of(new SessionResult.Participant(playerId, "Player", 10, 100, 120)), false);
    }
    private Settlement payment(Settlement.Status status, String caps) {
        return new Settlement(settlementId, sessionId, playerId, BigDecimal.ONE, new BigDecimal("100.00"),
                new BigDecimal("100.00"), caps, status, status == Settlement.Status.CONFIRMED ? started.plusSeconds(121) : null, null);
    }
    private List<String> messages() {
        ArgumentCaptor<Component> capture = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(capture.capture());
        return capture.getAllValues().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }
}
