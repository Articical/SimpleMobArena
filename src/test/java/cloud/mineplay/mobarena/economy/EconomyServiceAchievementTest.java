package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.storage.SettlementOutbox;
import cloud.mineplay.mobarena.storage.SettlementStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
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

class EconomyServiceAchievementTest {
    @TempDir Path directory;
    private JavaPlugin plugin;
    private EconomyService service;
    private final ConcurrentLinkedQueue<Runnable> mainTasks = new ConcurrentLinkedQueue<>();

    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("achievement-ledger-test"));
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.isEnabled()).thenReturn(true);
        when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),anyLong(),anyLong())).thenAnswer(call -> mock(BukkitTask.class));
        when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call -> {
            mainTasks.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
    }
    @AfterEach void close() { if (service != null) service.close(); }

    @Test void committedTypedTotalsUseTheWorkerAndCheckpointReturnsToTheMainThread() throws Exception {
        AtomicReference<String> storeThread = new AtomicReference<>();
        service = new EconomyService(plugin,mock(VaultGateway.class),settings -> {
            storeThread.set(Thread.currentThread().getName()); return new SettlementStore(settings);
        });
        List<UUID> notices = new ArrayList<>();
        service.setAchievementCheckpoint(notices::add);
        SessionResult result = result();
        UUID player = result.participants().getFirst().playerId();
        service.finish(result).get(5,TimeUnit.SECONDS);
        assertEquals("SimpleMobArena-ledger",storeThread.get());
        assertTrue(notices.isEmpty());
        assertEquals(Map.of("ZOMBIE",2L,"HUSK",1L),service.mobKills(player).get(5,TimeUnit.SECONDS));
        assertEquals(Map.of("ZOMBIE",2,"HUSK",1),service.matchMobKills(result.sessionId(),player).get(5,TimeUnit.SECONDS));
        var progress = service.achievementProgress(player).get(5,TimeUnit.SECONDS);
        assertEquals(3L,progress.totals().get("total_kills"));
        assertEquals(2L,progress.totals().get("zombie_kills"));
        assertEquals(List.of(player),service.achievementPlayers(null,100).get(5,TimeUnit.SECONDS));
        runMainTasks();
        // Startup replay may race a first finish; duplicate absolute-sync requests are harmless.
        assertFalse(notices.isEmpty());
        assertTrue(notices.stream().allMatch(player::equals));
    }

    @Test void failedOptionalFeedbackCannotUndoOrDuplicateCommittedKills() throws Exception {
        service = new EconomyService(plugin,mock(VaultGateway.class),SettlementStore::new);
        service.setAchievementCheckpoint(player -> { throw new IllegalStateException("simulated provider failure"); });
        SessionResult result = result();
        service.finish(result).get(5,TimeUnit.SECONDS);
        assertDoesNotThrow(this::runMainTasks);
        service.finish(result).get(5,TimeUnit.SECONDS);
        assertEquals(3L,service.achievementProgress(result.participants().getFirst().playerId()).get(5,TimeUnit.SECONDS).totals().get("total_kills"));
        assertTrue(new SettlementOutbox(directory.resolve("settlement-outbox")).entries().isEmpty());
    }

    @Test void liveFeedbackUsesCoalescedCommittedKillsBeforeTheMatchEndsWithoutDoubleCountingAtFinish() throws Exception {
        service = new EconomyService(plugin,mock(VaultGateway.class),SettlementStore::new);
        SessionResult result = result();
        UUID player = result.participants().getFirst().playerId();
        List<AchievementProgress> feedback = new ArrayList<>();
        service.setLiveAchievementCheckpoint(feedback::add);
        service.recordMobKills(result.sessionId(),player,"ArenaPlayer",Map.of("ZOMBIE",1));
        service.recordMobKills(result.sessionId(),player,"ArenaPlayer",Map.of("ZOMBIE",2,"HUSK",1));
        service.recordMobKills(result.sessionId(),player,"ArenaPlayer",Map.of("ZOMBIE",1));
        assertEquals(0L,service.achievementProgress(player).get(5,TimeUnit.SECONDS).totals().get("total_kills"));

        service.flushMobKills();
        var progress = service.achievementProgress(player).get(5,TimeUnit.SECONDS);
        assertEquals(3L,progress.totals().get("total_kills"));
        assertEquals(2L,progress.totals().get("zombie_kills"));
        assertEquals(0L,progress.totals().get("matches_played"));
        assertEquals(0L,service.stats(player).get(5,TimeUnit.SECONDS).kills());
        assertTrue(feedback.isEmpty());
        runMainTasks();
        assertEquals(List.of(progress),feedback);

        service.finish(result).get(5,TimeUnit.SECONDS);
        service.recordMobKills(result.sessionId(),player,"ArenaPlayer",Map.of("ZOMBIE",2,"HUSK",1));
        service.flushMobKills();
        assertEquals(3L,service.achievementProgress(player).get(5,TimeUnit.SECONDS).totals().get("total_kills"));
        assertEquals(3L,service.stats(player).get(5,TimeUnit.SECONDS).kills());
        assertEquals(1L,service.achievementProgress(player).get(5,TimeUnit.SECONDS).totals().get("matches_played"));
    }

    @Test void startupReplayRetainsTypedKillsAndCanRequestAnAchievementCheckpoint() throws Exception {
        SessionResult result = result();
        new SettlementOutbox(directory.resolve("settlement-outbox")).save(result,EconomySettings.defaults());
        CountDownLatch opened = new CountDownLatch(1), release = new CountDownLatch(1);
        service = new EconomyService(plugin,mock(VaultGateway.class),settings -> {
            opened.countDown();
            if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("startup timed out");
            return new SettlementStore(settings);
        });
        assertTrue(opened.await(5,TimeUnit.SECONDS));
        List<UUID> notices = new ArrayList<>();
        service.setAchievementCheckpoint(notices::add);
        release.countDown();
        UUID player = result.participants().getFirst().playerId();
        assertEquals(2L,service.achievementProgress(player).get(5,TimeUnit.SECONDS).totals().get("zombie_kills"));
        runMainTasks();
        assertEquals(List.of(player),notices);
        assertTrue(new SettlementOutbox(directory.resolve("settlement-outbox")).entries().isEmpty());
    }

    @Test void busyArenasCannotStarvePlayersBeyondTheFirstCheckpointBatch() throws Exception {
        service = new EconomyService(plugin,mock(VaultGateway.class),SettlementStore::new);
        UUID session = UUID.randomUUID();
        List<UUID> players = java.util.stream.IntStream.range(0,40).mapToObj(ignored -> UUID.randomUUID()).toList();
        for (UUID player : players) service.recordMobKills(session,player,"ArenaPlayer",Map.of("ZOMBIE",1));
        service.flushMobKills();
        assertEquals(1L,service.achievementProgress(players.getFirst()).get(5,TimeUnit.SECONDS).totals().get("zombie_kills"));
        assertFalse(service.achievementProgress(players.getLast()).get(5,TimeUnit.SECONDS).totals().containsKey("zombie_kills"));
        // The busiest players become dirty again, behind those still waiting for their first checkpoint.
        for (UUID player : players.subList(0,32)) service.recordMobKills(session,player,"ArenaPlayer",Map.of("ZOMBIE",2));
        service.flushMobKills();
        assertEquals(1L,service.achievementProgress(players.getLast()).get(5,TimeUnit.SECONDS).totals().get("zombie_kills"));
    }

    private void runMainTasks() { for (Runnable task; (task=mainTasks.poll())!=null;) task.run(); }
    private static SessionResult result() {
        Instant start = Instant.parse("2026-11-10T12:00:00Z");
        return new SessionResult(UUID.randomUUID(),"spawn",start,start.plusSeconds(120),3,BigDecimal.ZERO,
            List.of(new SessionResult.Participant(UUID.randomUUID(),"ArenaPlayer",3,42,120,Map.of("ZOMBIE",2,"HUSK",1))),false);
    }
}
