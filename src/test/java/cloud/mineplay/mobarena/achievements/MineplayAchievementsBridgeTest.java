package cloud.mineplay.mobarena.achievements;

import cloud.mineplay.mobarena.economy.AchievementProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.co.mineplay.achievements.api.AchievementDefinition;
import uk.co.mineplay.achievements.api.Achievements;
import uk.co.mineplay.achievements.api.PlayerSnapshot;
import uk.co.mineplay.achievements.api.ProgressSource;
import uk.co.mineplay.achievements.api.Rarity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MineplayAchievementsBridgeTest {
    private JavaPlugin plugin;
    private Plugin provider;
    private Server server;
    private BukkitScheduler scheduler;
    private BukkitTask timer;
    private AchievementCatalog catalog;
    private Achievements api;
    private MineplayAchievementsBridge.ProgressReader reader;
    private MineplayAchievementsBridge bridge;
    private final AtomicReference<RegisteredServiceProvider<Achievements>> registration = new AtomicReference<>();
    private final ConcurrentLinkedQueue<Runnable> mainTasks = new ConcurrentLinkedQueue<>();
    private final AtomicLong total = new AtomicLong(17);
    private final UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final ProgressSource kills = new ProgressSource("mineplay_mobarena:total_kills", "SimpleMobArena");
    private final ProgressSource zombies = new ProgressSource("mineplay_mobarena:zombie_kills", "SimpleMobArena");
    private final AchievementDefinition definition = new AchievementDefinition("mineplay_mobarena:kills_10", "SimpleMobArena",
        "Mob Arena", "Getting Started", "Kill 10 arena mobs.", Rarity.COMMON, 10, kills.id(), 10,
        List.of(), false, null, true, List.of(), "minecraft:iron_sword", null, 1);

    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        provider = mock(Plugin.class);
        when(provider.isEnabled()).thenReturn(true);
        server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        timer = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(server.isPrimaryThread()).thenReturn(true);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        ServicesManager services = mock(ServicesManager.class);
        when(server.getServicesManager()).thenReturn(services);
        when(services.getRegistration(Achievements.class)).thenAnswer(call -> registration.get());
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            mainTasks.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L))).thenReturn(timer);
        catalog = mock(AchievementCatalog.class);
        when(catalog.enabled()).thenReturn(true);
        when(catalog.sources()).thenReturn(List.of(kills, zombies));
        when(catalog.definitions()).thenReturn(List.of(definition));
        api = service();
        reader = mock(MineplayAchievementsBridge.ProgressReader.class);
        when(reader.players(any(), anyInt())).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(reader.progress(any())).thenAnswer(call -> CompletableFuture.completedFuture(
            new AchievementProgress(call.getArgument(0), "Player", Map.of("total_kills", total.get(), "zombie_kills", total.get()))));
        bridge = new MineplayAchievementsBridge(plugin, reader, catalog);
    }

    @AfterEach void close() { when(server.isPrimaryThread()).thenReturn(true); bridge.close(); }

    @Test void absenceRetainsRequestsAndServicePublicationRegistersBeforeReporting() {
        bridge.start();
        assertTrue(bridge.requestSync(player));
        bridge.tick(); drain();
        assertTrue(bridge.status().contains("Waiting"));
        verifyNoInteractions(api, reader);
        CompletableFuture<Void> registering = new CompletableFuture<>();
        when(api.registerProgressSource(kills)).thenReturn(registering);
        publish(api);
        bridge.registered(registrationEvent());
        bridge.tick(); drain();
        verify(reader, never()).progress(any());
        registering.complete(null); drain();
        var order = inOrder(api);
        order.verify(api).registerProgressSource(kills);
        order.verify(api).registerProgressSource(zombies);
        order.verify(api).registerAchievement(definition);
        order.verify(api).setProgress(player, kills.id(), 17);
        order.verify(api).setProgress(player, zombies.id(), 17);
        verify(api, never()).load(any(), any());
        verify(api, never()).unload(any());
    }

    @Test void failedRegistrationRetriesWithOneAttemptAndNeverPublishesPrematurely() {
        when(api.registerAchievement(definition)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("revision conflict")));
        publish(api); bridge.start(); drain();
        bridge.requestSync(player);
        verify(reader, never()).progress(any());
        assertTrue(bridge.status().contains("Registration pending"));
        bridge.tick(); drain(); // First retry after 1 second; the next retry is 2 seconds later.
        bridge.tick(); drain();
        verify(api, times(2)).registerAchievement(definition);
        when(api.registerAchievement(definition)).thenReturn(CompletableFuture.completedFuture(null));
        bridge.tick(); drain();
        assertTrue(bridge.status().startsWith("Ready"));
        verify(api).setProgress(player, kills.id(), 17);
    }

    @Test void replacedServiceRejectsLateRegistrationCallbacksAndContinuesWithNewService() {
        CompletableFuture<Void> oldRegistration = new CompletableFuture<>();
        when(api.registerProgressSource(kills)).thenReturn(oldRegistration);
        publish(api); bridge.start();
        Achievements replacement = service();
        publish(replacement);
        bridge.registered(registrationEvent()); drain();
        oldRegistration.complete(null); drain();
        bridge.requestSync(player); bridge.tick(); drain();
        verify(api, never()).registerProgressSource(zombies);
        verify(api, never()).setProgress(any(), anyString(), anyLong());
        verify(replacement).setProgress(player, zombies.id(), 17);
    }

    @Test void unregistrationStopsTheRemainingSourcesOfAnInflightPlayerAndReplaysLatestTotals() {
        ready();
        CompletableFuture<PlayerSnapshot> firstWrite = new CompletableFuture<>();
        when(api.setProgress(player, kills.id(), 17)).thenReturn(firstWrite);
        bridge.requestSync(player); bridge.tick(); drain();
        RegisteredServiceProvider<Achievements> removed = registration.getAndSet(null);
        ServiceUnregisterEvent event = mock(ServiceUnregisterEvent.class);
        doReturn(removed).when(event).getProvider();
        bridge.unregistered(event);
        firstWrite.complete(null); drain();
        verify(api, never()).setProgress(player, zombies.id(), 17);
        total.set(23);
        Achievements replacement = service(); publish(replacement);
        bridge.registered(registrationEvent()); bridge.tick(); drain();
        verify(replacement).setProgress(player, kills.id(), 23);
        verify(replacement).setProgress(player, zombies.id(), 23);
    }

    @Test void failedProgressFetchesTheLatestAuthoritativeTotalRatherThanReplayingAnIncrement() {
        ready();
        when(api.setProgress(player, zombies.id(), 17)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
        bridge.requestSync(player); bridge.tick(); drain();
        total.set(29);
        bridge.tick(); drain();
        verify(reader, times(2)).progress(player);
        verify(api).setProgress(player, kills.id(), 17);
        verify(api).setProgress(player, kills.id(), 29);
        verify(api).setProgress(player, zombies.id(), 29);
    }

    @Test void synchronousServiceRejectionIsRetriedLikeAnExceptionalFuture() {
        ready();
        when(api.setProgress(player, kills.id(), 17)).thenThrow(new IllegalStateException("temporarily disabled"));
        bridge.requestSync(player); bridge.tick(); drain();
        assertTrue(bridge.status().contains("retrying progress sync"));
        doReturn(CompletableFuture.completedFuture(null)).when(api).setProgress(player, kills.id(), 17);
        bridge.tick(); drain();
        verify(api).setProgress(player, zombies.id(), 17);
    }

    @Test void backgroundCompletionOnlyChangesBridgeStateThroughScheduledMainThreadContinuation() throws Exception {
        CompletableFuture<Void> registrationFuture = new CompletableFuture<>();
        when(api.registerAchievement(definition)).thenReturn(registrationFuture);
        publish(api); bridge.start(); bridge.requestSync(player);
        CompletableFuture.runAsync(() -> registrationFuture.complete(null)).get(3, TimeUnit.SECONDS);
        assertTrue(bridge.status().startsWith("Registering"));
        verify(reader, never()).progress(any());
        drain(); bridge.tick(); drain();
        verify(api).setProgress(player, kills.id(), 17);
    }

    @Test void aCheckpointDuringInflightReadIsNotLost() {
        ready();
        CompletableFuture<AchievementProgress> firstRead = new CompletableFuture<>();
        when(reader.progress(player)).thenReturn(firstRead).thenAnswer(call -> CompletableFuture.completedFuture(
            new AchievementProgress(player, "Player", Map.of("total_kills", 40L))));
        bridge.requestSync(player); bridge.tick();
        bridge.requestSync(player);
        firstRead.complete(new AchievementProgress(player, "Player", Map.of("total_kills", 30L))); drain();
        verify(api).setProgress(player, kills.id(), 30);
        verify(api).setProgress(player, kills.id(), 40);
        verify(api, times(2)).setProgress(player, zombies.id(), 0);
    }

    @Test void paginatedOfflineReplayIsBoundedAndPeriodicallyRescansCommittedPlayers() {
        List<UUID> first = new ArrayList<>();
        for (int i=1;i<=64;i++) first.add(new UUID(0,i));
        UUID tail = new UUID(0,65);
        when(reader.players(isNull(), eq(64))).thenReturn(CompletableFuture.completedFuture(first));
        when(reader.players(eq(first.getLast()), eq(64))).thenReturn(CompletableFuture.completedFuture(List.of(tail)));
        ready();
        bridge.tick(); drain();
        verify(reader, times(4)).progress(any());
        for (int tick=0;tick<16;tick++) { bridge.tick(); drain(); }
        verify(reader).progress(tail);
        verify(reader, times(65)).progress(any());
        for (int tick=0;tick<300;tick++) { bridge.tick(); drain(); }
        verify(reader, atLeast(2)).players(isNull(), eq(64));
    }

    @Test void historyFailureRetriesTheSameCursorAndMalformedPagesCannotLoopForever() {
        when(reader.players(isNull(), eq(64))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException()))
            .thenReturn(CompletableFuture.completedFuture(List.of(player, player)))
            .thenReturn(CompletableFuture.completedFuture(List.of(player)));
        ready();
        bridge.tick(); drain(); // Invalid duplicate page; back off again.
        bridge.tick(); drain();
        verify(reader, times(2)).players(isNull(), eq(64));
        bridge.tick(); drain();
        verify(reader).progress(player);
    }

    @Test void disabledCatalogAndClosedBridgeDoNotRegisterOrWriteAnything() {
        when(catalog.enabled()).thenReturn(false);
        publish(api); bridge.start(); assertFalse(bridge.requestSync(player)); bridge.tick(); drain();
        assertTrue(bridge.status().startsWith("Disabled"));
        verifyNoInteractions(api, reader);
        verify(scheduler, never()).runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong());
        bridge.close();
        when(catalog.enabled()).thenReturn(true);
        assertFalse(bridge.requestSync(player)); drain();
        verifyNoInteractions(api, reader);
    }

    @Test void shutdownRejectsLateCallbacksAndCancelsItsTimerWithoutUnloadingSharedSnapshots() {
        ready();
        CompletableFuture<AchievementProgress> read = new CompletableFuture<>();
        when(reader.progress(player)).thenReturn(read);
        bridge.requestSync(player); bridge.tick();
        bridge.close();
        read.complete(new AchievementProgress(player, "Player", Map.of("total_kills", 99L))); drain();
        verify(api, never()).setProgress(any(), anyString(), anyLong());
        verify(api, never()).unload(any());
        verify(timer).cancel();
    }

    @Test void loginRequestsAStoredCheckpointAndNoPlayerOrServiceCallsBlock() {
        ready();
        Player joining = mock(Player.class); when(joining.getUniqueId()).thenReturn(player);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class); when(event.getPlayer()).thenReturn(joining);
        bridge.joined(event); bridge.tick(); drain();
        verify(reader).progress(player);
        verify(api).setProgress(player, zombies.id(), 17);
    }

    @Test void committedKillsUpdateLiveTrackerWithoutWaitingForHistoryOrMatchEnd() {
        ProgressSource matches = new ProgressSource("mineplay_mobarena:matches_played", "SimpleMobArena");
        when(catalog.sources()).thenReturn(List.of(kills, zombies, matches));
        ready(); // The historical player budget remains zero until the first one-second timer tick.
        bridge.reportLive(new AchievementProgress(player, "Player",
            Map.of("total_kills", 100L, "zombie_kills", 30L, "matches_played", 10L)));
        drain();
        verify(api).reportLiveProgress(player, kills.id(), 100);
        verify(api).reportLiveProgress(player, zombies.id(), 30);
        verify(api, never()).reportLiveProgress(eq(player), eq(matches.id()), anyLong());
        verify(api, never()).setProgress(any(), anyString(), anyLong());
        verify(reader, never()).progress(any());
        verify(api, never()).load(any(), any());
        verify(api, never()).unload(any());
    }

    @Test void liveUpdatesCoalesceToNewestCommittedSnapshotDuringInflightCall() {
        ready();
        CompletableFuture<Void> first = new CompletableFuture<>();
        when(api.reportLiveProgress(player, kills.id(), 10)).thenReturn(first);
        bridge.reportLive(new AchievementProgress(player, "Player", Map.of("total_kills", 10L, "zombie_kills", 5L)));
        for (long count = 11; count <= 100; count++)
            bridge.reportLive(new AchievementProgress(player, "Player", Map.of("total_kills", count, "zombie_kills", count - 5)));
        verify(api, times(1)).reportLiveProgress(any(), anyString(), anyLong());
        first.complete(null); drain();
        verify(api).reportLiveProgress(player, kills.id(), 100);
        verify(api).reportLiveProgress(player, zombies.id(), 95);
        verify(api, times(4)).reportLiveProgress(any(), anyString(), anyLong());
        bridge.reportLive(new AchievementProgress(player, "Player", Map.of("total_kills", 100L, "zombie_kills", 95L)));
        drain();
        verify(api, times(4)).reportLiveProgress(any(), anyString(), anyLong());
    }

    @Test void liveFailureRetriesNewestSnapshotAndQueuesAuthoritativeDurableReplay() {
        ready();
        CompletableFuture<Void> first = new CompletableFuture<>();
        when(api.reportLiveProgress(player, kills.id(), 10)).thenReturn(first);
        bridge.reportLive(new AchievementProgress(player, "Player", Map.of("total_kills", 10L)));
        bridge.reportLive(new AchievementProgress(player, "Player", Map.of("total_kills", 30L, "zombie_kills", 15L)));
        first.completeExceptionally(new IllegalStateException("temporarily unavailable")); drain();
        total.set(40);
        bridge.tick(); drain();
        verify(api).reportLiveProgress(player, kills.id(), 30);
        verify(api).reportLiveProgress(player, zombies.id(), 15);
        verify(reader).progress(player);
        verify(api).setProgress(player, kills.id(), 40);
        verify(api, times(1)).reportLiveProgress(player, kills.id(), 10);
    }

    @Test void liveReplacementStopsOldCallsAndNewServiceReceivesFreshSnapshot() {
        ready();
        CompletableFuture<Void> first = new CompletableFuture<>();
        when(api.reportLiveProgress(player, kills.id(), 10)).thenReturn(first);
        bridge.reportLive(new AchievementProgress(player, "Player", Map.of("total_kills", 10L, "zombie_kills", 5L)));
        Achievements replacement = service(); publish(replacement);
        bridge.registered(registrationEvent()); drain();
        bridge.reportLive(new AchievementProgress(player, "Player", Map.of("total_kills", 20L, "zombie_kills", 10L)));
        first.complete(null); drain();
        verify(api, never()).reportLiveProgress(player, zombies.id(), 5);
        verify(replacement).reportLiveProgress(player, kills.id(), 20);
        verify(replacement).reportLiveProgress(player, zombies.id(), 10);
    }

    @Test void missingLiveServiceKeepsTheCommittedPlayerQueuedForReplay() {
        bridge.start();
        bridge.reportLive(new AchievementProgress(player, "Player", Map.of("total_kills", 10L)));
        verifyNoInteractions(api, reader);
        publish(api); bridge.registered(registrationEvent()); drain();
        bridge.tick(); drain();
        verify(reader).progress(player);
        verify(api).setProgress(player, kills.id(), 17);
    }

    private Achievements service() {
        Achievements result = mock(Achievements.class);
        when(result.registerProgressSource(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(result.registerAchievement(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(result.setProgress(any(), anyString(), anyLong())).thenReturn(CompletableFuture.completedFuture(null));
        when(result.reportLiveProgress(any(), anyString(), anyLong())).thenReturn(CompletableFuture.completedFuture(null));
        return result;
    }

    private void publish(Achievements service) {
        registration.set(new RegisteredServiceProvider<>(Achievements.class, service, ServicePriority.Normal, provider));
    }

    private ServiceRegisterEvent registrationEvent() {
        ServiceRegisterEvent event = mock(ServiceRegisterEvent.class);
        doReturn(registration.get()).when(event).getProvider();
        return event;
    }

    private void ready() { publish(api); bridge.start(); drain(); }

    private void drain() {
        for (int limit=0;!mainTasks.isEmpty();limit++) {
            assertTrue(limit<10_000,"Unbounded scheduled work");
            mainTasks.remove().run();
        }
    }
}
