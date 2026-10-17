package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.storage.SettlementStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

/** Real SQLite/outbox/claim/complete pipeline with only the server and external provider simulated. */
class EconomyServiceSqliteTest {
    @TempDir Path directory;
    private final UUID playerId = UUID.randomUUID();
    private final LinkedBlockingQueue<Runnable> scheduled = new LinkedBlockingQueue<>();
    private final List<Runnable> timers = new ArrayList<>();
    private VaultGateway gateway;
    private Player player;
    private EconomyService service;

    @BeforeEach void setup() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        gateway = mock(VaultGateway.class);
        player = mock(Player.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("sqlite-reward-pipeline-test"));
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.isEnabled()).thenReturn(true);
        YamlConfiguration config = new YamlConfiguration();
        config.createSection("economy"); // Production defaults: three completed waves and 60 seconds.
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
        when(gateway.available(anyInt())).thenReturn(true);
        when(gateway.deposit(eq(playerId), any(), anyInt())).thenReturn(new PaymentOutcome(Settlement.Status.CONFIRMED, ""));
        service = new EconomyService(plugin, gateway, SettlementStore::new);
        service.stats(playerId).get(5, TimeUnit.SECONDS);
    }

    @AfterEach void cleanup() { if (service != null) service.close(); }

    @Test void oneCompletedWaveWithHundredDollarPotExplainsThreeWaveRequirementWithoutDepositing() throws Exception {
        SessionResult result = result(1, "100.00");

        service.finish(result).get(5, TimeUnit.SECONDS);
        Settlement payment = service.payouts(result.sessionId()).get(5, TimeUnit.SECONDS).getFirst();
        assertEquals(Settlement.Status.WITHHELD, payment.status());
        assertEquals(0, payment.intendedAmount().signum());
        assertTrue(payment.capsApplied().contains("MINIMUM_WAVE"));
        verify(player, never()).sendMessage(any(Component.class));
        nextTask().run();

        assertTrue(messages().getFirst().contains("No reward this match: your team needed to finish 3 waves"));
        assertFalse(messages().getFirst().contains("/ma history"));
        timers.getFirst().run();
        service.stats(playerId).get(5, TimeUnit.SECONDS);
        assertTrue(scheduled.isEmpty());
        verify(gateway, never()).deposit(any(), any(), anyInt());
        assertEquals(Settlement.Status.WITHHELD, service.history(playerId, 1).get(5, TimeUnit.SECONDS).getFirst().status());
    }

    @Test void eligibleThreeWaveMatchGetsReceiptOnlyAfterRealLedgerConfirmation() throws Exception {
        SessionResult result = result(3, "600.00");

        service.finish(result).get(5, TimeUnit.SECONDS);
        assertEquals(Settlement.Status.PENDING, service.payouts(result.sessionId()).get(5, TimeUnit.SECONDS).getFirst().status());
        nextTask().run();
        assertTrue(messages().getFirst().contains("Your reward: $600.00. Payment queued"));
        assertFalse(messages().getFirst().contains("Paid $"));

        timers.getFirst().run();
        Runnable depositTask = nextTask();
        assertEquals(Settlement.Status.PROCESSING, service.payouts(result.sessionId()).get(5, TimeUnit.SECONDS).getFirst().status());
        verify(gateway, never()).deposit(any(), any(), anyInt());
        depositTask.run();
        Settlement completed = service.payouts(result.sessionId()).get(5, TimeUnit.SECONDS).getFirst();
        assertEquals(Settlement.Status.CONFIRMED, completed.status());
        assertNotNull(completed.paidAt());
        assertEquals(new BigDecimal("600.00"), completed.intendedAmount());
        assertEquals(1, messages().size(), "The ledger worker cannot directly send the receipt");
        nextTask().run();

        assertTrue(messages().getLast().contains("roof: Paid $600.00."));
        verify(gateway, times(1)).deposit(eq(playerId), eq(new BigDecimal("600.00")), eq(2));
        assertEquals(new BigDecimal("600.00"), service.history(playerId, 1).get(5, TimeUnit.SECONDS).getFirst().confirmedAmount());
    }

    private SessionResult result(int completedWaves, String pot) {
        Instant finished = Instant.now();
        return new SessionResult(UUID.randomUUID(), "roof", finished.minusSeconds(120), finished, completedWaves, new BigDecimal(pot),
                List.of(new SessionResult.Participant(playerId, "SoloPlayer", 10, 100, 120)), false, "ALL_ELIMINATED");
    }
    private Runnable nextTask() throws InterruptedException {
        Runnable task = scheduled.poll(5, TimeUnit.SECONDS);
        assertNotNull(task, "Expected a main-thread task");
        return task;
    }
    private List<String> messages() {
        ArgumentCaptor<Component> capture = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(capture.capture());
        return capture.getAllValues().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }
}
