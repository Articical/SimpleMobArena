package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.duels.DuelResult;
import cloud.mineplay.mobarena.storage.DuelOutbox;
import cloud.mineplay.mobarena.storage.SettlementStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

class EconomyServiceDuelTest {
    @TempDir Path directory;
    private final List<Runnable> timers = new ArrayList<>();
    private JavaPlugin plugin;
    private VaultGateway gateway;
    private BukkitScheduler scheduler;
    private EconomyService service;

    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        gateway = mock(VaultGateway.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("duel-persistence-test"));
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.isEnabled()).thenReturn(true);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
            timers.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
    }

    @AfterEach void close() { if (service != null) service.close(); }

    @Test void completedDuelUsesBackgroundLedgerAndNeverSchedulesVaultOrRewardMessages() throws Exception {
        var result = result();
        var thread = new AtomicReference<String>();
        service = new EconomyService(plugin, gateway, settings -> {
            thread.set(Thread.currentThread().getName()); return new SettlementStore(settings);
        });
        service.finishDuel(result).get(5, TimeUnit.SECONDS);
        assertEquals("SimpleMobArena-ledger", thread.get());
        assertEquals(result, service.duelMatch(result.sessionId()).get(5, TimeUnit.SECONDS));
        assertEquals(List.of(result), service.duelHistory(DuelStoreTest.RED, 1).get(5, TimeUnit.SECONDS));
        assertEquals(0, service.stats(DuelStoreTest.RED).get(5, TimeUnit.SECONDS).kills());
        assertTrue(service.payouts(result.sessionId()).get(5, TimeUnit.SECONDS).isEmpty());
        assertTrue(new DuelOutbox(directory.resolve("duel-outbox")).entries().isEmpty());
        verify(scheduler, never()).runTask(eq(plugin), any(Runnable.class));
        verify(gateway, never()).deposit(any(), any(), anyInt());
    }

    @Test void localResultIsDurableEvenWhileDatabaseStartupIsBlocked() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var result = result();
        service = new EconomyService(plugin, gateway, settings -> {
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("Test startup timeout");
            return new SettlementStore(settings);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        try {
            var saving = service.finishDuel(result);
            assertFalse(saving.isDone(), "The SQL worker is intentionally blocked");
            Path pending = directory.resolve("duel-outbox").resolve(result.sessionId() + ".pending");
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (!Files.exists(pending)) Thread.sleep(5);
            });
            assertEquals(List.of(result), new DuelOutbox(directory.resolve("duel-outbox")).entries());
            release.countDown();
            saving.get(5, TimeUnit.SECONDS);
            assertEquals(result, service.duelMatch(result.sessionId()).get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @Test void failedConnectionRetainsDuelAndReconnectDrainsItOnce() throws Exception {
        var attempts = new AtomicInteger();
        var result = result();
        service = new EconomyService(plugin, gateway, settings -> {
            if (attempts.getAndIncrement() == 0) throw new SQLException("Simulated unavailable backend");
            return new SettlementStore(settings);
        });
        assertThrows(ExecutionException.class, () -> service.duelMatch(result.sessionId()).get(5, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> service.finishDuel(result).get(5, TimeUnit.SECONDS));
        assertEquals(List.of(result), new DuelOutbox(directory.resolve("duel-outbox")).entries());
        timers.get(1).run();
        assertEquals(result, service.duelMatch(result.sessionId()).get(5, TimeUnit.SECONDS));
        assertTrue(new DuelOutbox(directory.resolve("duel-outbox")).entries().isEmpty());
        assertEquals(1, service.duelHistory(DuelStoreTest.RED, 1).get(5, TimeUnit.SECONDS).size());
    }

    @Test void restartReplaysCommittedResultWithoutDuplicateRoster() throws Exception {
        var result = result();
        new DuelOutbox(directory.resolve("duel-outbox")).save(result);
        try (var store = new SettlementStore(directory.resolve("mobarena.db"))) { store.recordDuel(result); }
        service = new EconomyService(plugin, gateway, SettlementStore::new);
        assertEquals(result, service.duelMatch(result.sessionId()).get(5, TimeUnit.SECONDS));
        assertEquals(1, service.duelHistory(DuelStoreTest.RED, 1).get(5, TimeUnit.SECONDS).size());
        assertTrue(new DuelOutbox(directory.resolve("duel-outbox")).entries().isEmpty());
    }

    @Test void corruptOutboxStopsRecoveryAndPreservesEvidence() throws Exception {
        Path folder = directory.resolve("duel-outbox"); Files.createDirectories(folder);
        Path file = folder.resolve(UUID.randomUUID() + ".pending"); Files.write(file, new byte[]{1, 2, 3});
        service = new EconomyService(plugin, gateway, SettlementStore::new);
        assertThrows(ExecutionException.class, () -> service.duelHistory(DuelStoreTest.RED, 1).get(5, TimeUnit.SECONDS));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(file));
        verify(gateway, never()).deposit(any(), any(), anyInt());
    }

    private static DuelResult result() {
        return DuelStoreTest.result(UUID.randomUUID(), DuelStoreTest.START.plusSeconds(120));
    }
}
