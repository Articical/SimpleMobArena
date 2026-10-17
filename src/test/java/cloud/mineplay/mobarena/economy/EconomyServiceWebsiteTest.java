package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.storage.SettlementStore;
import cloud.mineplay.mobarena.website.WebsiteExportSettings;
import cloud.mineplay.mobarena.website.WebsitePublisher;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real ledger through the service boundary; no production HTTP endpoint is contacted. */
class EconomyServiceWebsiteTest {
    @TempDir Path directory;
    private final UUID source = UUID.randomUUID();
    private final List<Runnable> timers = new ArrayList<>();
    private final LinkedBlockingQueue<Runnable> scheduled = new LinkedBlockingQueue<>();
    private final AtomicReference<WebsitePublisher.Ledger> transportLedger = new AtomicReference<>();
    private final AtomicReference<SettlementStore> opened = new AtomicReference<>();
    private JavaPlugin plugin;
    private VaultGateway gateway;
    private WebsitePublisher publisher;
    private EconomyService service;

    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        gateway = mock(VaultGateway.class);
        publisher = mock(WebsitePublisher.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("website-service-test"));
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.isEnabled()).thenReturn(true);
        var configuration = new YamlConfiguration();
        configuration.createSection("economy");
        when(plugin.getConfig()).thenReturn(configuration);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
            timers.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            scheduled.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
    }

    @AfterEach void cleanup() { if (service != null) service.close(); }

    private WebsiteExportSettings settings(UUID sourceId) {
        var settings = mock(WebsiteExportSettings.class);
        when(settings.enabled()).thenReturn(true);
        when(settings.sourceId()).thenReturn(sourceId);
        return settings;
    }

    private void start(WebsiteExportSettings settings) throws Exception {
        service = new EconomyService(plugin, gateway, database -> {
            assertEquals("SimpleMobArena-ledger", Thread.currentThread().getName());
            var store = spy(new SettlementStore(database));
            opened.set(store);
            doAnswer(call -> {
                assertEquals("SimpleMobArena-ledger", Thread.currentThread().getName());
                return call.callRealMethod();
            }).when(store).configureWebsiteExport(anyBoolean(), any(), any());
            doAnswer(call -> {
                assertEquals("SimpleMobArena-ledger", Thread.currentThread().getName());
                return call.callRealMethod();
            }).when(store).websiteEvents(any(), anyInt());
            doAnswer(call -> {
                assertEquals("SimpleMobArena-ledger", Thread.currentThread().getName());
                return call.callRealMethod();
            }).when(store).websiteDelivered(any(), anyLong(), any());
            doAnswer(call -> {
                assertEquals("SimpleMobArena-ledger", Thread.currentThread().getName());
                return call.callRealMethod();
            }).when(store).websiteFailed(any(), anyString(), any(), anyBoolean());
            return store;
        }, settings, (config, ledger) -> {
            transportLedger.set(ledger);
            return publisher;
        });
        service.stats(UUID.randomUUID()).get(5, TimeUnit.SECONDS);
    }

    @Test void startupConfiguresCaptureBeforeRecoveryAndEveryHttpLedgerCallbackRunsOffServerThread() throws Exception {
        start(settings(source));
        var order = inOrder(opened.get());
        order.verify(opened.get()).configureWebsiteExport(eq(true), eq(source), any());
        order.verify(opened.get()).recover(any());
        verify(publisher, timeout(5000)).start();
        SessionResult result = match();
        service.finish(result).get(5, TimeUnit.SECONDS);
        var adapter = transportLedger.get();
        var event = adapter.events(Instant.now().plusSeconds(2), 10).get(5, TimeUnit.SECONDS).getFirst();
        adapter.failed(event.eventId(), "HTTP_503", Instant.now(), false).get(5, TimeUnit.SECONDS);
        assertEquals(1, service.websiteStatus().get(5, TimeUnit.SECONDS).queue().pending());
        adapter.delivered(event.eventId(), event.revision(), Instant.now()).get(5, TimeUnit.SECONDS);
        assertEquals(0, service.websiteStatus().get(5, TimeUnit.SECONDS).queue().pending());
        verify(gateway, never()).deposit(any(), any(), anyInt());
    }

    @Test void exportFailureAndRetryNeverIssuePaymentsOrFailTheEconomyWorker() throws Exception {
        start(settings(source));
        verify(publisher, timeout(5000)).start();
        var result = match();
        service.finish(result).get(5, TimeUnit.SECONDS);
        var event = transportLedger.get().events(Instant.now().plusSeconds(1), 10).get(5, TimeUnit.SECONDS).getFirst();
        transportLedger.get().failed(event.eventId(), "HTTP_401", Instant.now(), true).get(5, TimeUnit.SECONDS);
        assertTrue(service.websiteRetry(event.eventId()).get(5, TimeUnit.SECONDS));
        verify(publisher).resume();
        assertFalse(service.status().contains("ledger error"));
        assertEquals(Settlement.Status.PENDING, service.payouts(result.sessionId()).get(5, TimeUnit.SECONDS).getFirst().status());
        var next = transportLedger.get().events(Instant.now().plusSeconds(1), 10).get(5, TimeUnit.SECONDS).getFirst();
        assertArrayEquals(event.payload(), next.payload());
        verify(gateway, never()).deposit(any(), any(), anyInt());
    }

    @Test void deliveryMarkFailureIsSanitizedAndLeavesEventPendingWithoutStoppingPayments() throws Exception {
        start(settings(source));
        verify(publisher, timeout(5000)).start();
        service.finish(match()).get(5, TimeUnit.SECONDS);
        var event = transportLedger.get().events(Instant.now().plusSeconds(1), 10).get(5, TimeUnit.SECONDS).getFirst();
        doThrow(new SQLException("DO_NOT_EXPOSE_DB_SECRET")).when(opened.get()).websiteDelivered(any(), anyLong(), any());
        var failure = assertThrows(Exception.class, () -> transportLedger.get()
                .delivered(event.eventId(), event.revision(), Instant.now()).get(5, TimeUnit.SECONDS));
        assertFalse(failure.toString().contains("DO_NOT_EXPOSE"));
        assertEquals(1, service.websiteStatus().get(5, TimeUnit.SECONDS).queue().pending());
        assertFalse(service.status().contains("ledger error"));
    }

    @Test void sourceMismatchPausesExportPreservesIdentityAndAllowsOrdinarySettlements() throws Exception {
        try (var prior = new SettlementStore(directory.resolve("mobarena.db"))) { prior.configureWebsiteExport(true, source, Instant.now()); }
        start(settings(UUID.randomUUID()));
        var status = service.websiteStatus().get(5, TimeUnit.SECONDS);
        assertTrue(status.paused());
        assertEquals("WEBSITE_SOURCE_MISMATCH", status.failureCode());
        assertEquals(source, status.queue().sourceId());
        verify(publisher, never()).start();
        var result = match();
        service.finish(result).get(5, TimeUnit.SECONDS);
        assertEquals(1, service.payouts(result.sessionId()).get(5, TimeUnit.SECONDS).size());
        assertFalse(service.status().contains("ledger error"));
        assertThrows(Exception.class, () -> service.websiteBackfill().get(5, TimeUnit.SECONDS));
    }

    @Test void backfillRunsInBatchesOnLedgerWorkerAndCanResumeAfterRestart() throws Exception {
        try (var prior = new SettlementStore(directory.resolve("mobarena.db"))) {
            prior.prepare(match(), EconomySettings.defaults(), Instant.now());
            prior.configureWebsiteExport(true, source, Instant.now());
            prior.websiteStartBackfill(Instant.now());
        }
        start(settings(source));
        doAnswer(call -> {
            assertEquals("SimpleMobArena-ledger", Thread.currentThread().getName());
            return call.callRealMethod();
        }).when(opened.get()).websiteBackfillBatch(anyInt(), any());
        timers.get(2).run();
        assertEquals(1, service.websiteStatus().get(5, TimeUnit.SECONDS).queue().pending());
        timers.get(2).run();
        assertFalse(service.websiteStatus().get(5, TimeUnit.SECONDS).queue().backfillActive());
        verify(gateway, never()).deposit(any(), any(), anyInt());
    }

    @Test void disabledConfigurationRetainsQueueWithoutStartingHttpWorker() throws Exception {
        try (var prior = new SettlementStore(directory.resolve("mobarena.db"))) {
            prior.configureWebsiteExport(true, source, Instant.now());
            prior.prepare(match(), EconomySettings.defaults(), Instant.now());
        }
        start(WebsiteExportSettings.disabled());
        var status = service.websiteStatus().get(5, TimeUnit.SECONDS);
        assertTrue(status.paused());
        assertFalse(status.enabled());
        assertEquals(1, status.queue().pending());
        assertEquals(source, status.queue().sourceId());
        verify(publisher, never()).start();
    }

    @Test void pendingWebsiteOutageDoesNotDelayOrDuplicateAnEligibleVaultPayment() throws Exception {
        when(gateway.available(anyInt())).thenReturn(true);
        when(gateway.deposit(any(), any(), anyInt())).thenReturn(new PaymentOutcome(Settlement.Status.CONFIRMED, ""));
        start(settings(source));
        verify(publisher, timeout(5000)).start();
        var result = match();
        service.finish(result).get(5, TimeUnit.SECONDS);
        var event = transportLedger.get().events(Instant.now().plusSeconds(1), 10).get(5, TimeUnit.SECONDS).getFirst();
        transportLedger.get().failed(event.eventId(), "HTTP_503", Instant.now().plusSeconds(3600), false).get(5, TimeUnit.SECONDS);
        // Run any queued chat notice before asking the normal payment timer for a claim.
        for (Runnable notice; (notice = scheduled.poll()) != null;) notice.run();
        timers.getFirst().run();
        Runnable deposit = scheduled.poll(5, TimeUnit.SECONDS);
        assertNotNull(deposit);
        deposit.run();
        var payment = service.payouts(result.sessionId()).get(5, TimeUnit.SECONDS).getFirst();
        assertEquals(Settlement.Status.CONFIRMED, payment.status());
        assertEquals(new BigDecimal("600.00"), payment.intendedAmount());
        assertEquals(3, service.websiteStatus().get(5, TimeUnit.SECONDS).queue().pending());
        assertNull(service.websiteStatus().get(5, TimeUnit.SECONDS).queue().lastAcknowledgedAt());
        verify(gateway, times(1)).deposit(any(), eq(new BigDecimal("600.00")), eq(2));
    }

    @Test void databaseFailureDuringCaptureConfigurationDoesNotSilentlyDisableRequiredCapture() throws Exception {
        service = new EconomyService(plugin, gateway, database -> {
            var store = spy(new SettlementStore(database));
            opened.set(store);
            doThrow(new SQLException("injected source transaction failure")).when(store)
                    .configureWebsiteExport(eq(true), eq(source), any());
            return store;
        }, settings(source), (config, ledger) -> publisher);
        assertThrows(Exception.class, () -> service.stats(UUID.randomUUID()).get(5, TimeUnit.SECONDS));
        verify(opened.get(), never()).configureWebsiteExport(eq(false), any(), any());
        verify(opened.get(), never()).recover(any());
        verify(publisher, never()).start();
    }

    private SessionResult match() {
        Instant end = Instant.now();
        return new SessionResult(UUID.randomUUID(), "roof", end.minusSeconds(120), end, 3,
                new BigDecimal("600.00"), List.of(new SessionResult.Participant(UUID.randomUUID(), ".Bedrock Player", 10, 100, 120)),
                false, "ALL_ELIMINATED");
    }
}

