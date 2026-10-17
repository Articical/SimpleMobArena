package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.storage.SettlementStore;
import cloud.mineplay.mobarena.website.WebsitePublisher;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

/** Real SQLite payments and a controllable clock: no sleeping or server-thread database work. */
class EconomyServicePaymentDelayTest {
    @TempDir Path directory;
    private final UUID playerId = UUID.randomUUID();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    private final List<Runnable> timers = new ArrayList<>();
    private final LinkedBlockingQueue<Runnable> scheduled = new LinkedBlockingQueue<>();
    private JavaPlugin plugin;
    private VaultGateway gateway;
    private Clock clock;
    private EconomyService service;
    private YamlConfiguration config;

    @BeforeEach void setup() throws Exception {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        gateway = mock(VaultGateway.class);
        clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> now.get());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("payment-delay-test"));
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.isEnabled()).thenReturn(true);
        config = new YamlConfiguration();
        config.createSection("economy");
        when(plugin.getConfig()).thenReturn(config);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
            timers.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            scheduled.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(gateway.available(anyInt())).thenReturn(true);
        when(gateway.deposit(eq(playerId), any(), anyInt())).thenReturn(new PaymentOutcome(Settlement.Status.CONFIRMED, ""));
        start();
    }

    @AfterEach void cleanup() { if (service != null) service.close(); }

    @Test void defaultDelayKeepsPaymentPendingUntilThreeSecondsThenDepositsExactlyOnce() throws Exception {
        UUID match = prepare();
        tick();
        assertPending(match);
        now.updateAndGet(time -> time.plusSeconds(2));
        tick();
        assertPending(match);
        now.updateAndGet(time -> time.plusSeconds(1));
        tick();
        assertEquals(Settlement.Status.PROCESSING, payment(match).status());
        verify(gateway, never()).deposit(any(), any(), anyInt());
        Runnable deposit = scheduled.poll(5, TimeUnit.SECONDS);
        assertNotNull(deposit);
        deposit.run();
        assertEquals(Settlement.Status.CONFIRMED, payment(match).status());
        drain();
        now.updateAndGet(time -> time.plusSeconds(30));
        tick();
        assertTrue(scheduled.isEmpty());
        verify(gateway, times(1)).deposit(eq(playerId), eq(new BigDecimal("600.00")), eq(2));
    }

    @Test void restartKeepsPendingRewardAndStartsANewShortDelayWithoutUncertainPayment() throws Exception {
        UUID match = prepare();
        tick();
        service.close();
        service = null;
        timers.clear();
        start();
        now.updateAndGet(time -> time.plusSeconds(5));
        tick();
        assertPending(match);
        now.updateAndGet(time -> time.plusSeconds(3));
        tick();
        assertEquals(Settlement.Status.PROCESSING, payment(match).status());
        assertNotNull(scheduled.poll(5, TimeUnit.SECONDS));
        verify(gateway, never()).deposit(any(), any(), anyInt());
    }

    @Test void zeroDisablesDelayAndInvalidConfigurationPausesPayments() throws Exception {
        UUID match = prepare();
        config.set("economy.payment-delay-seconds", -1);
        assertFalse(service.reload(config.getConfigurationSection("economy")));
        tick();
        assertPending(match);
        config.set("economy.payment-delay-seconds", 0);
        assertTrue(service.reload(config.getConfigurationSection("economy")));
        tick();
        assertEquals(Settlement.Status.PROCESSING, payment(match).status());
        assertNotNull(scheduled.poll(5, TimeUnit.SECONDS));
    }

    @Test void delayConfigurationRejectsFractionOverflowTextAndBoolean() {
        for (Object value : List.of(-1, 31, Long.MAX_VALUE, 1.5, "3", true)) {
            config.set("economy.payment-delay-seconds", value);
            assertThrows(IllegalArgumentException.class, () -> PaymentDelay.seconds(config.getConfigurationSection("economy")));
        }
        config.set("economy.payment-delay-seconds", 30);
        assertEquals(30, PaymentDelay.seconds(config.getConfigurationSection("economy")));
    }

    private void start() throws Exception {
        Thread serverThread = Thread.currentThread();
        service = new EconomyService(plugin, gateway, database -> {
            assertNotEquals(serverThread, Thread.currentThread(), "Database must open off the server thread");
            return new SettlementStore(database);
        }, null, WebsitePublisher::new, clock);
        service.stats(playerId).get(5, TimeUnit.SECONDS);
    }
    private UUID prepare() throws Exception {
        UUID match = UUID.randomUUID();
        service.finish(new SessionResult(match, "roof", now.get().minusSeconds(120), now.get(), 3,
                new BigDecimal("600.00"), List.of(new SessionResult.Participant(playerId, "Player", 10, 100, 120)),
                false, "ALL_ELIMINATED")).get(5, TimeUnit.SECONDS);
        drain(); // Pending notice; an offline player is deliberately harmless.
        return match;
    }
    private Settlement payment(UUID match) throws Exception { return service.payouts(match).get(5, TimeUnit.SECONDS).getFirst(); }
    private void tick() throws Exception {
        timers.getFirst().run();
        service.stats(playerId).get(5, TimeUnit.SECONDS);
    }
    private void assertPending(UUID match) throws Exception {
        assertEquals(Settlement.Status.PENDING, payment(match).status());
        assertTrue(scheduled.isEmpty());
        verify(gateway, never()).deposit(any(), any(), anyInt());
    }
    private void drain() { Runnable task; while ((task = scheduled.poll()) != null) task.run(); }
}
