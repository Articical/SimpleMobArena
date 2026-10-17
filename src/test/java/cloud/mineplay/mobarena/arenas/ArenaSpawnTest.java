package cloud.mineplay.mobarena.arenas;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.config.ArenaDefinition;
import cloud.mineplay.mobarena.config.SpawnSafety;
import cloud.mineplay.mobarena.economy.EconomyService;
import cloud.mineplay.mobarena.economy.EconomySettings;
import cloud.mineplay.mobarena.economy.SessionResult;
import cloud.mineplay.mobarena.enums.GameState;
import cloud.mineplay.mobarena.equipment.EquipmentService;
import cloud.mineplay.mobarena.session.Participation;
import io.papermc.paper.registry.RegistryAccess;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real match pipeline with Paper's spawn callback order and personal elimination results. */
class ArenaSpawnTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<SpawnSafety> safety;
    private SimpleMobArena plugin;
    private ArenaManager manager;
    private EquipmentService equipment;
    private EconomyService economy;
    private Logger logger;
    private World world;
    private Chunk chunk;
    private Player player;
    private Zombie mob;
    private Arena arena;
    private UUID playerId, mobId;
    private final AtomicBoolean addedToWorld = new AtomicBoolean();
    private final AtomicBoolean burnsInDay = new AtomicBoolean(true);
    private final AtomicInteger fireTicks = new AtomicInteger();
    private final AtomicLong currentTick = new AtomicLong(1);
    private final List<Player> onlinePlayers = new ArrayList<>();
    private boolean resetSunlightDuringSpawnEvent;
    private CreatureSpawnEvent spawnEvent;

    @BeforeAll static void initializeTestAttributes() {
        // Paper's Attribute constants resolve through the server registry. Bootstrap only
        // identities for the mocked AttributeInstances; no server or game logic is emulated.
        try (MockedStatic<RegistryAccess> registryAccess = mockStatic(RegistryAccess.class)) {
            RegistryAccess access = mock(RegistryAccess.class, RETURNS_DEEP_STUBS);
            registryAccess.when(RegistryAccess::registryAccess).thenReturn(access);
            Registry<Attribute> attributes = Registry.ATTRIBUTE;
            // doAnswer avoids first evaluating a deep-stubbed generic return as Attribute.
            doAnswer(call -> new TestAttribute(call.getArgument(0)))
                .when(attributes).getOrThrow(any(Key.class));
            assertNotNull(Attribute.MAX_HEALTH);
        }
    }

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        safety = mockStatic(SpawnSafety.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        PluginManager plugins = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        plugin = mock(SimpleMobArena.class);
        manager = mock(ArenaManager.class);
        equipment = mock(EquipmentService.class);
        economy = mock(EconomyService.class);
        logger = mock(Logger.class);
        when(plugin.getArenaManager()).thenReturn(manager);
        when(plugin.getEquipmentService()).thenReturn(equipment);
        when(plugin.getEconomyService()).thenReturn(economy);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.ticks()).thenAnswer(call -> currentTick.get());
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getOnlinePlayers()).thenAnswer(call -> List.copyOf(onlinePlayers));
        when(economy.finish(any(SessionResult.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(economy.rewardSettings()).thenReturn(EconomySettings.defaults());
        NamespacedKey sessionKey = new NamespacedKey("simplemobarena", "session");
        when(plugin.sessionKey()).thenReturn(sessionKey);

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("waves.base-mobs", 1);
        defaults.set("waves.mobs-per-wave-per-player", 0);
        defaults.set("waves.mobs.ZOMBIE.unlock-wave", 1);
        defaults.set("waves.mobs.ZOMBIE.weight", 1);
        defaults.set("waves.mobs.ZOMBIE.chicken-jockey-chance", 0.0);
        for (String event : List.of("join", "countdown", "start", "end")) defaults.set("announcements.public." + event, false);
        when(plugin.getConfig()).thenReturn(defaults);
        YamlConfiguration config = new YamlConfiguration();
        config.set("world", "world");
        config.set("enabled", true);
        config.set("countdown-seconds", 0);
        config.set("pos1", "world,0,63,0");
        config.set("pos2", "world,10,70,10");
        config.set("player-spawns", List.of("world,2.5,64,2.5"));
        config.set("mob-spawns", List.of("world,4.5,64,4.5"));
        config.set("exit", "world,12.5,64,2.5");
        ArenaDefinition definition = new ArenaDefinition("spawn", config, defaults);
        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        arena = new Arena(plugin, definition);

        chunk = mock(Chunk.class);
        when(world.getChunkAt(0, 0)).thenReturn(chunk);
        when(chunk.getEntities()).thenAnswer(call -> addedToWorld.get() ? new Entity[]{mob} : new Entity[0]);
        playerId = UUID.randomUUID();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("ArenaPlayer");
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(definition.playerSpawns.getFirst());
        when(player.teleport(any(Location.class))).thenReturn(true);
        AttributeInstance playerHealth = mock(AttributeInstance.class);
        when(playerHealth.getValue()).thenReturn(20.0);
        when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(playerHealth);
        bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
        onlinePlayers.add(player);

        mobId = UUID.randomUUID();
        mob = mock(Zombie.class);
        when(mob.getUniqueId()).thenReturn(mobId);
        when(mob.getType()).thenReturn(EntityType.ZOMBIE);
        when(mob.getLocation()).thenReturn(definition.mobSpawns.getFirst());
        when(mob.isValid()).thenReturn(true);
        doAnswer(call -> { burnsInDay.set(call.getArgument(0)); return null; }).when(mob).setShouldBurnInDay(anyBoolean());
        doAnswer(call -> { fireTicks.set(call.getArgument(0)); return null; }).when(mob).setFireTicks(anyInt());
        when(mob.getFireTicks()).thenAnswer(call -> fireTicks.get());
        AttributeInstance mobHealth = mock(AttributeInstance.class);
        when(mobHealth.getBaseValue()).thenReturn(20.0);
        when(mob.getAttribute(Attribute.MAX_HEALTH)).thenReturn(mobHealth);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        AtomicReference<String> tag = new AtomicReference<>();
        when(mob.getPersistentDataContainer()).thenReturn(data);
        doAnswer(call -> { tag.set(call.getArgument(2)); return null; })
            .when(data).set(eq(sessionKey), eq(PersistentDataType.STRING), anyString());
        when(data.get(sessionKey, PersistentDataType.STRING)).thenAnswer(call -> tag.get());
        bukkit.when(() -> Bukkit.getEntity(mobId)).thenAnswer(call -> addedToWorld.get() ? mob : null);
        doAnswer(call -> { addedToWorld.set(false); return null; }).when(mob).remove();

        when(world.spawn(any(Location.class), eq(Zombie.class), eq(CreatureSpawnEvent.SpawnReason.CUSTOM), eq(false), any()))
            .thenAnswer(call -> {
                assertFalse(arena.owns(mob), "The entity begins without an arena tag.");
                Consumer<Zombie> initializer = call.getArgument(4);
                initializer.accept(mob);
                // Paper invokes the initializer before its cancellable CreatureSpawnEvent.
                assertTrue(arena.owns(mob), "Ownership must exist when protection listeners inspect the entity.");
                spawnEvent = new CreatureSpawnEvent(mob, call.getArgument(2));
                spawnEvent.setCancelled(!mob.isValid() || mob.isDead());
                plugins.callEvent(spawnEvent);
                if (resetSunlightDuringSpawnEvent) {
                    mob.setShouldBurnInDay(true);
                    mob.setFireTicks(80);
                }
                addedToWorld.set(!spawnEvent.isCancelled());
                return mob;
            });
    }

    @AfterEach void cleanup() {
        safety.close();
        bukkit.close();
    }

    @Test void successfulCustomSpawnIsTaggedBeforeTheSpawnEventAndRemainsTracked() {
        beginFirstWave();
        assertEquals(GameState.ACTIVE, arena.getArenaState());
        assertEquals(1, arena.getCurrentWave());
        assertEquals(java.util.Set.of(mobId), arena.getActiveMobs());
        assertTrue(addedToWorld.get());
        assertFalse(spawnEvent.isCancelled());
        assertEquals(CreatureSpawnEvent.SpawnReason.CUSTOM, spawnEvent.getSpawnReason());
        verify(mob).setPersistent(false);
        verify(mob).setRemoveWhenFarAway(false);
        verify(mob, times(2)).setShouldBurnInDay(false);
        verify(player).playSound(any(Location.class), eq("minecraft:block.note_block.pling"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
        verify(player).playSound(any(Location.class), eq("minecraft:block.note_block.bell"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
        verify(player, never()).playSound(any(Location.class), eq("minecraft:block.note_block.chime"), any(SoundCategory.class), anyFloat(), anyFloat());
        verify(mob, never()).remove();
        verify(equipment, never()).restore(any(), any(), any());
        verify(economy, never()).finish(any());
    }

    @Test void sunlightProtectionIsReappliedAfterSpawnListenersCanOverwriteIt() {
        resetSunlightDuringSpawnEvent = true;
        beginFirstWave();
        assertEquals(GameState.ACTIVE, arena.getArenaState());
        assertFalse(burnsInDay.get(), "Protection must survive another listener changing the pre-spawn value.");
        assertEquals(0, fireTicks.get(), "Initial sun ignition must be cleared after the entity is inserted.");
        org.mockito.InOrder order = inOrder(mob);
        order.verify(mob).setShouldBurnInDay(false);
        order.verify(mob).setShouldBurnInDay(true);
        order.verify(mob).setShouldBurnInDay(false);
        order.verify(mob).setFireTicks(0);
    }

    @Test void invalidMobFromCancelledSpawnRecoversPlayersAndExplainsRegionFlags() {
        when(mob.isValid()).thenReturn(false);
        assertRejectedSpawn();
    }

    @Test void deadMobReturnedFromSpawnIsAlsoRejectedAndCleanedUp() {
        when(mob.isDead()).thenReturn(true);
        assertRejectedSpawn();
    }

    @Test void initializerFailureCancelsTheMatchAndLogsTheOriginalThrowable() {
        IllegalArgumentException failure = new IllegalArgumentException("fixture health initializer failed");
        doThrow(failure).when(mob).setHealth(anyDouble());
        beginFirstWave();
        assertCancelledAndRecovered();
        assertNull(spawnEvent, "An initializer failure must occur before entity insertion/spawn event.");
        assertFalse(addedToWorld.get());
        verify(logger).log(eq(Level.WARNING), contains("wave 1 spawn failed (session"), same(failure));
        assertTrue(messages().stream().anyMatch(message -> message.contains("Check the server console for the cause")));
    }

    @Test void lastPlayerEliminationGetsPersonalResultsAndSettlesEarnedPotExactlyOnce() throws Exception {
        when(economy.waveReward(1, 1)).thenReturn(new BigDecimal("100.00"));
        when(economy.capPot(any(BigDecimal.class))).thenAnswer(call -> call.getArgument(0));
        beginFirstWave();
        arena.damage(playerId, 37.5);
        addedToWorld.set(false);
        arena.mobDeath(mob, player);
        assertEquals(GameState.INTERMISSION, arena.getArenaState());
        assertEquals(1, arena.getCompletedWaves());
        assertEquals(1, arena.getKills(playerId));
        verify(player).playSound(any(Location.class), eq("minecraft:entity.experience_orb.pickup"), eq(SoundCategory.MASTER), eq(.7F), eq(1.2F));
        ageMatchBySeconds(125);
        UUID completedSession = arena.getSessionId();
        clearInvocations(player, manager, equipment, economy);
        doAnswer(call -> {
            assertFalse(arena.isParticipant(playerId), "The summary must still reach a player already removed from the active set.");
            return null;
        }).when(player).showTitle(any(Title.class));

        arena.eliminate(player, "was eliminated");
        arena.eliminate(player, "was eliminated"); // A repeated lethal event must be harmless.

        ArgumentCaptor<SessionResult> results = ArgumentCaptor.forClass(SessionResult.class);
        verify(economy, times(1)).finish(results.capture());
        SessionResult result = results.getValue();
        assertEquals(completedSession, result.sessionId());
        assertFalse(result.cancelled(), "Normal defeat must retain eligibility for the earned pot.");
        assertEquals(1, result.completedWaves());
        assertEquals(new BigDecimal("100.00"), result.prizePot());
        assertEquals(1, result.participants().size());
        SessionResult.Participant contribution = result.participants().getFirst();
        assertEquals(playerId, contribution.playerId());
        assertEquals(1, contribution.kills());
        assertEquals(37.5, contribution.damage());
        assertTrue(contribution.participationSeconds() >= 125);
        assertEquals(GameState.WAITING, arena.getArenaState());
        assertTrue(arena.activeIds().isEmpty());

        ArgumentCaptor<Title> title = ArgumentCaptor.forClass(Title.class);
        verify(player, times(1)).showTitle(title.capture());
        assertEquals("ELIMINATED", plain(title.getValue().title()));
        verify(player, never()).clearTitle();
        assertTrue(plain(title.getValue().subtitle()).contains("1 kills"));
        long seconds = contribution.participationSeconds();
        String time = String.format(java.util.Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60);
        List<String> personal = messages();
        assertEquals(2, personal.size(), "Elimination must send one personal summary, even when invoked twice.");
        assertTrue(personal.stream().anyMatch(message -> message.contains("1 kills") && message.contains("37.5 damage") && message.contains(time)));
        assertTrue(personal.stream().anyMatch(message -> message.contains("Waves completed: 1")));
        assertFalse(personal.stream().anyMatch(message -> message.contains("Estimated share")));
        assertFalse(personal.stream().anyMatch(message -> message.contains("Calculating")));

        org.mockito.InOrder delivery = inOrder(player, manager, equipment);
        delivery.verify(player).showTitle(any(Title.class));
        delivery.verify(player, times(2)).sendMessage(any(Component.class));
        delivery.verify(manager).release(playerId);
        delivery.verify(equipment).restore(eq(player), eq(arena.definition().exit), any());
        verify(manager, times(1)).release(playerId);
        verify(equipment, times(1)).restore(eq(player), eq(arena.definition().exit), any());
        verify(player).playSound(any(Location.class), eq("minecraft:block.note_block.bass"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
        verify(player, never()).playSound(any(Location.class), eq("minecraft:entity.player.levelup"), any(SoundCategory.class), anyFloat(), anyFloat());
    }

    @Test void finalWaveShowsOneFinishAnnouncementAndPersonalStatsWithoutRewardProgressSpam() throws Exception {
        arena.getConfiguration().set("max-waves", 1);
        configureCountdown(0, 1, false);
        when(economy.waveReward(1, 1)).thenReturn(new BigDecimal("100.00"));
        when(economy.capPot(any(BigDecimal.class))).thenAnswer(call -> call.getArgument(0));
        beginFirstWave();
        ageMatchBySeconds(125);
        arena.damage(playerId, 37.5);
        clearInvocations(player, manager, equipment, economy);

        addedToWorld.set(false);
        arena.mobDeath(mob, player);

        assertEquals(GameState.WAITING, arena.getArenaState());
        List<String> feedback = messages();
        assertEquals(3, feedback.size());
        assertEquals(1, feedback.stream().filter(message -> message.contains("finished with 1 wave cleared")).count());
        assertEquals(1, feedback.stream().filter(message -> message.contains("Finished ") && message.contains("1 kills")
                && message.contains("37.5 damage")).count());
        assertTrue(feedback.stream().anyMatch(message -> message.contains("Waves completed: 1")));
        assertFalse(feedback.stream().anyMatch(message -> message.contains("No rewards yet") || message.contains("Prize pot")
                || message.contains("All waves completed") || message.contains("Calculating")));
        verify(economy, times(1)).finish(any(SessionResult.class));
        verify(equipment).restore(eq(player), eq(arena.definition().exit), any());
        verify(player, times(1)).playSound(any(Location.class), eq("minecraft:entity.player.levelup"), any(SoundCategory.class), anyFloat(), anyFloat());
    }

    @Test void multiplayerEliminationSummarizesDepartingPlayerAndAnnouncesToSurvivor() {
        Player survivor = mock(Player.class);
        UUID survivorId = UUID.randomUUID();
        when(survivor.getUniqueId()).thenReturn(survivorId);
        when(survivor.getName()).thenReturn("Survivor");
        when(survivor.isOnline()).thenReturn(true);
        when(survivor.teleport(any(Location.class))).thenReturn(true);
        when(survivor.getLocation()).thenReturn(arena.definition().playerSpawns.getFirst());
        AttributeInstance survivorHealth = player.getAttribute(Attribute.MAX_HEALTH);
        when(survivor.getAttribute(Attribute.MAX_HEALTH)).thenReturn(survivorHealth);
        bukkit.when(() -> Bukkit.getPlayer(survivorId)).thenReturn(survivor);
        arena.reserve();
        arena.joined(player);
        arena.reserve();
        arena.joined(survivor);
        arena.tick(1);
        arena.damage(playerId, 12.5);
        clearInvocations(player, survivor, manager, equipment, economy);

        arena.eliminate(player, "was eliminated");
        arena.eliminate(player, "was eliminated");

        assertEquals(GameState.ACTIVE, arena.getArenaState());
        assertEquals(java.util.Set.of(survivorId), arena.activeIds());
        assertEquals(java.util.Set.of(mobId), arena.getActiveMobs());
        verify(economy, never()).finish(any());
        verify(player, times(1)).showTitle(any(Title.class));
        verify(survivor, never()).showTitle(any(Title.class));
        List<String> personal = messages();
        assertEquals(2, personal.size());
        assertTrue(personal.stream().anyMatch(message -> message.contains("12.5 damage")));
        assertTrue(personal.stream().anyMatch(message -> message.contains("Your reward is worked out when the match ends")));
        ArgumentCaptor<Component> announcements = ArgumentCaptor.forClass(Component.class);
        verify(survivor, times(1)).sendMessage(announcements.capture());
        assertTrue(plain(announcements.getValue()).contains("ArenaPlayer was eliminated"));
        verify(manager, times(1)).release(playerId);
        verify(equipment, times(1)).restore(eq(player), eq(arena.definition().exit), any());
        verify(equipment, never()).restore(eq(survivor), any(), any());
        verify(mob, never()).remove();
    }

    @Test void brokenTitleAndChatDeliveryCannotPreventLastPlayerCleanupAndSettlement() {
        beginFirstWave();
        clearInvocations(player, manager, equipment, economy, logger);
        IllegalStateException titleFailure = new IllegalStateException("fixture title delivery failed");
        IllegalStateException chatFailure = new IllegalStateException("fixture chat delivery failed");
        doThrow(titleFailure).when(player).showTitle(any(Title.class));
        doThrow(chatFailure).when(player).sendMessage(any(Component.class));

        assertDoesNotThrow(() -> arena.eliminate(player, "was eliminated"));
        assertDoesNotThrow(() -> arena.eliminate(player, "was eliminated"));

        ArgumentCaptor<SessionResult> results = ArgumentCaptor.forClass(SessionResult.class);
        verify(economy, times(1)).finish(results.capture());
        assertFalse(results.getValue().cancelled());
        assertEquals(playerId, results.getValue().participants().getFirst().playerId());
        verify(player, times(1)).showTitle(any(Title.class));
        verify(player, times(1)).sendMessage(any(Component.class));
        verify(logger).log(eq(Level.WARNING), contains("match cleanup continues"), same(titleFailure));
        verify(logger).log(eq(Level.WARNING), contains("match cleanup continues"), same(chatFailure));
        verify(manager, times(1)).release(playerId);
        verify(equipment, times(1)).restore(eq(player), eq(arena.definition().exit), any());
        verify(chunk).removePluginChunkTicket(plugin);
        verify(mob).remove();
        assertEquals(GameState.WAITING, arena.getArenaState());
        assertTrue(arena.activeIds().isEmpty());
        assertTrue(arena.getActiveMobs().isEmpty());
    }

    @Test void joiningAndFirstCompletedWaveExplainRewardEligibility() {
        when(economy.waveReward(1, 1)).thenReturn(new BigDecimal("100.00"));
        when(economy.capPot(any(BigDecimal.class))).thenAnswer(call -> call.getArgument(0));
        arena.reserve();
        arena.joined(player);
        assertTrue(messages().stream().anyMatch(message -> message.contains("your team needs to finish 3 waves")
            && message.contains("60s played") && message.contains("Rewards are paid after the match")));
        arena.tick(1);
        addedToWorld.set(false);
        arena.mobDeath(mob, player);

        assertEquals(GameState.INTERMISSION, arena.getArenaState());
        assertTrue(messages().stream().anyMatch(message -> message.contains("No rewards yet: 1/3 waves cleared")
            && message.contains("60s played")));
        verify(economy, never()).finish(any());
    }

    @Test void creditedKillPlaysOneCueOnlyForKillerEvenWhenDuplicateDeathArrivesDuringActiveWave() {
        Player teammate = onlinePlayer("Teammate");
        Player observer = onlinePlayer("Observer");
        configureWaveWithPendingMob();
        admit(player);
        admit(teammate);
        tickAt(1);
        assertEquals(java.util.Set.of(mobId), arena.getActiveMobs());

        addedToWorld.set(false);
        arena.mobDeath(mob, player);
        assertEquals(GameState.ACTIVE, arena.getArenaState(), "The second pending spawn keeps this wave active for a meaningful duplicate-death check.");
        arena.mobDeath(mob, player);

        assertEquals(1, arena.getKills(playerId));
        assertEquals(0, arena.getKills(teammate.getUniqueId()));
        assertTrue(arena.getActiveMobs().isEmpty());
        verify(player, times(1)).playSound(any(Location.class), eq("minecraft:entity.experience_orb.pickup"), eq(SoundCategory.MASTER), eq(.4F), eq(1.7F));
        assertNoKillCue(teammate);
        assertNoKillCue(observer);
        verify(economy, never()).waveReward(anyInt(), anyInt());
    }

    @Test void environmentalMobDeathDoesNotCreditOrPlayKillCue() {
        configureWaveWithPendingMob();
        beginFirstWave();
        arena.mobDeath(mob, null);

        assertEquals(0, arena.getKills(playerId));
        assertTrue(arena.getActiveMobs().isEmpty(), "An environmental death still removes the tracked mob.");
        assertNoKillCue(player);
    }

    @Test void outsiderKillingTrackedMobDoesNotCreditOrPlayKillCue() {
        Player observer = onlinePlayer("Observer");
        configureWaveWithPendingMob();
        beginFirstWave();
        arena.mobDeath(mob, observer);

        assertEquals(0, arena.getTotalKills());
        assertTrue(arena.getActiveMobs().isEmpty());
        assertNoKillCue(player);
        assertNoKillCue(observer);
    }

    @Test void participantOutsideArenaGetsNoKillCreditOrCue() {
        configureWaveWithPendingMob();
        beginFirstWave();
        when(player.getLocation()).thenReturn(new Location(world, 500, 64, 500));
        arena.mobDeath(mob, player);

        assertEquals(0, arena.getKills(playerId));
        assertTrue(arena.getActiveMobs().isEmpty());
        assertNoKillCue(player);
    }

    @Test void eliminatedPlayerGetsNoKillCreditOrCueWhileSurvivorContinues() {
        Player survivor = onlinePlayer("Survivor");
        configureWaveWithPendingMob();
        admit(player);
        admit(survivor);
        tickAt(1);
        arena.eliminate(player, "was eliminated");
        arena.mobDeath(mob, player);

        assertEquals(GameState.ACTIVE, arena.getArenaState());
        assertEquals(java.util.Set.of(survivor.getUniqueId()), arena.activeIds());
        assertEquals(0, arena.getTotalKills());
        assertNoKillCue(player);
        assertNoKillCue(survivor);
    }

    @Test void staleSessionAndUntrackedMobDeathsDoNotCreditOrPlayKillCue() {
        beginFirstWave();
        // Reusing the tracked UUID must not let a mob tagged for an old session count.
        Zombie stale = taggedMob(mobId, UUID.randomUUID());
        Zombie untracked = taggedMob(UUID.randomUUID(), arena.getSessionId());
        arena.mobDeath(stale, player);
        arena.mobDeath(untracked, player);

        assertEquals(GameState.ACTIVE, arena.getArenaState());
        assertEquals(java.util.Set.of(mobId), arena.getActiveMobs());
        assertEquals(0, arena.getKills(playerId));
        assertNoKillCue(player);
        verify(economy, never()).waveReward(anyInt(), anyInt());
    }

    @Test void privateCountdownTitlesUseActualRemainingSecondsWithoutRepeatsOrBurstsAfterLag() {
        Player observer = onlinePlayer("Observer");
        configureCountdown(15, 1, true);
        currentTick.set(7);
        admit(player);
        tickAt(8);
        tickAt(26);
        tickAt(107); // Ten seconds left, although this is not a multiple of 20.
        tickAt(108);
        tickAt(227); // Four seconds left: collapse the crossed five-second threshold.
        tickAt(267); // Cross three and two, announcing only the current two seconds.
        tickAt(268);

        assertEquals(List.of(15, 10, 4, 2), countdownNotices(player));
        assertNoPrivateCountdownFor(observer);
        assertTrue(sent(player).stream().noneMatch(message -> message.contains("starts in")), "The countdown belongs on screen rather than in chat.");
        verify(player, times(4)).playSound(any(Location.class), eq("minecraft:block.note_block.hat"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
        verify(observer, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
        assertEquals(GameState.COUNTDOWN, arena.getArenaState());
    }

    @Test void unlistedInitialCountdownStillAppearsWithAnEmptyFollowUpSchedule() {
        Player observer = onlinePlayer("Observer");
        plugin.getConfig().set("announcements.countdown-seconds", List.of());
        configureCountdown(7, 1, true);
        admit(player);
        tickAt(41);
        tickAt(121);
        assertEquals(List.of(7), countdownNotices(player));
        tickAt(141);
        assertEquals(GameState.ACTIVE, arena.getArenaState());
        assertEquals(1, sent(observer).stream().filter(message -> message.contains("has started with")).count());
        assertEquals(1, titles(player).stream().filter("FIGHT!"::equals).count());
        assertNoPrivateCountdownFor(observer);
        verify(player, times(1)).playSound(any(Location.class), eq("minecraft:block.note_block.hat"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
        verify(player, times(1)).playSound(any(Location.class), eq("minecraft:block.note_block.bell"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
    }

    @Test void zeroCountdownWaitsForPendingEquipmentBeforeAnyStartNoticeOrSound() {
        Player observer = onlinePlayer("Observer");
        configureCountdown(0, 1, true);
        admit(player);
        arena.reserve(); // A second player's equipment save has not completed.
        tickAt(1);
        tickAt(21);
        tickAt(41);
        assertEquals(GameState.COUNTDOWN, arena.getArenaState());
        assertTrue(titles(player).isEmpty(), "A zero-second countdown must not display a premature start title while equipment is saving.");
        assertNoPrivateCountdownFor(observer);
        assertTrue(sent(observer).stream().noneMatch(message -> message.contains("has started with")));
        verify(player, never()).playSound(any(Location.class), eq("minecraft:block.note_block.bell"), any(SoundCategory.class), anyFloat(), anyFloat());

        arena.releaseReservation(); // Failed preparation releases its reservation.
        tickAt(42);
        tickAt(43);
        assertEquals(GameState.ACTIVE, arena.getArenaState());
        assertEquals(1, arena.getStartingPlayerCount());
        assertEquals(1, sent(observer).stream().filter(message -> message.contains("has started with")).count());
        assertEquals(List.of("FIGHT!"), titles(player));
        assertNoPrivateCountdownFor(observer);
        verify(player, times(1)).playSound(any(Location.class), eq("minecraft:block.note_block.bell"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
    }

    @Test void droppingBelowMinimumPausesPrivateCountdownAndRejoiningRestartsItsFullDuration() {
        Player observer = onlinePlayer("Observer");
        Player second = onlinePlayer("Second");
        configureCountdown(5, 2, true);
        admit(player);
        assertEquals(GameState.WAITING, arena.getArenaState());
        assertTrue(titles(player).isEmpty());
        currentTick.set(7);
        admit(second);
        tickAt(47);
        arena.eliminate(second, "left the match");
        assertEquals(GameState.WAITING, arena.getArenaState());
        assertEquals(1, sent(player).stream().filter(message -> message.contains("Waiting for players in")).count());
        assertEquals(1, titles(player).stream().filter("COUNTDOWN PAUSED"::equals).count());
        assertEquals(List.of(5, 3), countdownNotices(second));
        verify(second, times(1)).clearTitle();
        assertTrue(titles(second).stream().noneMatch("COUNTDOWN PAUSED"::equals), "The departing player must not receive the remaining lobby's pause title.");
        assertNoPrivateCountdownFor(observer);
        tickAt(107); // The old countdown deadline must not start the reduced group.
        assertEquals(GameState.WAITING, arena.getArenaState());

        currentTick.set(117);
        admit(second);
        assertEquals(List.of(5, 3, 5), countdownNotices(player));
        tickAt(197);
        assertEquals(GameState.COUNTDOWN, arena.getArenaState());
        tickAt(217);
        assertEquals(GameState.ACTIVE, arena.getArenaState());
        assertEquals(List.of(5, 3, 5, 1), countdownNotices(player));
        assertEquals(1, sent(observer).stream().filter(message -> message.contains("has started with 2 players")).count());
        assertEquals(1, titles(player).stream().filter("FIGHT!"::equals).count());
        assertEquals(1, titles(second).stream().filter("FIGHT!"::equals).count());
        assertNoPrivateCountdownFor(observer);
    }

    @Test void manualStartStopsAllLaterCountdownNotices() {
        Player observer = onlinePlayer("Observer");
        configureCountdown(15, 1, true);
        admit(player);
        currentTick.set(2);
        arena.startArena();
        tickAt(107);
        tickAt(307);
        assertEquals(GameState.ACTIVE, arena.getArenaState());
        assertEquals(List.of(15), countdownNotices(player));
        assertEquals(1, titles(player).stream().filter("FIGHT!"::equals).count());
        assertNoPrivateCountdownFor(observer);
        assertEquals(1, sent(observer).stream().filter(message -> message.contains("has started with")).count());
        verify(player, times(1)).playSound(any(Location.class), eq("minecraft:block.note_block.bell"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
    }

    @Test void cancellationClearsCountdownAndQuietIdleStopsDoNotBroadcastPhantomMatches() {
        Player observer = onlinePlayer("Observer");
        configureCountdown(15, 1, true);
        arena.endArena();
        arena.endArena();
        assertTrue(sent(observer).isEmpty());
        admit(player);
        tickAt(81);
        arena.endArena();
        assertEquals(1, sent(observer).stream().filter(message -> message.contains("was cancelled after")).count());
        verify(player, times(1)).clearTitle();
        verify(observer, never()).clearTitle();
        verify(player, times(1)).playSound(any(Location.class), eq("minecraft:block.note_block.didgeridoo"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
        tickAt(401);
        arena.endArena();
        assertTrue(sent(observer).stream().noneMatch(message -> message.contains("has started with")));
        assertEquals(1, sent(observer).stream().filter(message -> message.contains("was cancelled after")).count());
        admit(player);
        tickAt(501);
        assertEquals(List.of(15, 15, 10), countdownNotices(player));
        assertNoPrivateCountdownFor(observer);
        verify(economy, never()).finish(any());
    }

    @Test void malformedCountdownScheduleWarnsAndFallsBackToTheDefaultThresholds() {
        Player observer = onlinePlayer("Observer");
        plugin.getConfig().set("announcements.countdown-seconds", "not a list");
        configureCountdown(15, 1, true);
        admit(player);
        tickAt(101);
        assertEquals(List.of(15, 10), countdownNotices(player));
        assertNoPrivateCountdownFor(observer);
        verify(logger).warning(contains("using default countdown announcement times"));
    }

    @Test void failedOptionalSoundPlaybackDoesNotStopAdmissionOrMatchStart() {
        IllegalStateException soundFailure = new IllegalStateException("fixture sound playback failed");
        doThrow(soundFailure).when(player).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
        assertDoesNotThrow(this::beginFirstWave);
        assertEquals(GameState.ACTIVE, arena.getArenaState());
        assertEquals(java.util.Set.of(mobId), arena.getActiveMobs());
        verify(logger).log(eq(Level.WARNING), contains("arena sound JOIN"), same(soundFailure));
        verify(logger).log(eq(Level.WARNING), contains("arena sound MATCH_START"), same(soundFailure));
        verify(economy, never()).finish(any());
    }

    @Test void defaultSoloWaveSpawnsSixMobsWithNormalHealth() {
        DynamicSpawns spawns = configureScalingScenario(config -> {});
        startPlayers(1);
        spawns.completeWave();

        assertEquals(6, spawns.inWave(1).size());
        spawns.inWave(1).forEach(spawn -> spawn.assertHealth(20));
        assertTrue(sent(player).stream().anyMatch(message -> message.contains("Wave 1") && message.contains("6 mobs incoming")));
        verify(economy).waveReward(1, 1);
    }

    @Test void defaultFourPlayerWaveSpawnsNineMobsWithScaledHealth() {
        DynamicSpawns spawns = configureScalingScenario(config -> {});
        startPlayers(4);
        spawns.completeWave();

        assertEquals(9, spawns.inWave(1).size());
        spawns.inWave(1).forEach(spawn -> spawn.assertHealth(29));
        assertTrue(sent(player).stream().anyMatch(message -> message.contains("Wave 1") && message.contains("9 mobs incoming")));
        verify(economy).waveReward(1, 4);
    }

    @Test void departuresCannotReduceLaterBatchesOrSubsequentWavesInTheSameMatch() {
        DynamicSpawns spawns = configureScalingScenario(config -> {});
        List<Player> starters = startPlayers(4);
        assertEquals(1, spawns.spawned.size(), "The first batch must precede the departures.");
        spawns.spawned.getFirst().assertHealth(29);
        for (Player departing : starters.subList(1, starters.size())) arena.eliminate(departing, "left the match");
        assertEquals(java.util.Set.of(playerId), arena.activeIds());

        spawns.completeWave();
        tickAt(currentTick.get() + 1);
        assertEquals(2, arena.getCurrentWave());
        spawns.completeWave();

        assertEquals(4, arena.getStartingPlayerCount());
        assertEquals(9, spawns.inWave(1).size());
        assertEquals(13, spawns.inWave(2).size());
        spawns.spawned.forEach(spawn -> spawn.assertHealth(29));
        verify(economy).waveReward(1, 4);
        verify(economy).waveReward(2, 4);
        verify(economy, never()).waveReward(anyInt(), eq(1));
    }

    @Test void disablingPlayerHealthScalingPreservesPlayerBasedMobCounts() {
        DynamicSpawns spawns = configureScalingScenario(config -> config.set("waves.player-health-scaling.enabled", false));
        startPlayers(4);
        spawns.completeWave();

        assertEquals(9, spawns.inWave(1).size());
        spawns.inWave(1).forEach(spawn -> spawn.assertHealth(20));
    }

    @Test void customAdditionalPlayerHealthMultiplierRespectsItsConfiguredCap() {
        DynamicSpawns spawns = configureScalingScenario(config -> {
            config.set("waves.player-health-scaling.additional-player-multiplier", .5);
            config.set("waves.player-health-scaling.maximum-multiplier", 1.6);
        });
        startPlayers(4);
        spawns.completeWave();

        assertEquals(9, spawns.inWave(1).size());
        spawns.inWave(1).forEach(spawn -> spawn.assertHealth(32)); // min(1 + 3*.5, 1.6) * 20
    }

    @Test void playerHealthScalingCombinesWithTheExistingWaveSixHealthIncrease() {
        DynamicSpawns spawns = configureScalingScenario(config -> {
            config.set("waves.base-mobs", 1);
            config.set("waves.mobs-per-wave-per-player", 0);
        });
        startPlayers(4);
        for (int wave = 1; wave <= 6; wave++) {
            assertEquals(wave, arena.getCurrentWave());
            spawns.completeWave();
            spawns.inWave(wave).getFirst().assertHealth(wave < 6 ? 29 : 34.8);
            if (wave < 6) tickAt(currentTick.get() + 1);
        }
        assertEquals(6, spawns.spawned.size());
    }

    @Test void combinedMobDifficultyWaveAndPlayerHealthCannotExceedTheEntityHealthCap() {
        arena.getConfiguration().set("difficulty-multiplier", 2);
        DynamicSpawns spawns = configureScalingScenario(config -> {
            config.set("waves.base-mobs", 1);
            config.set("waves.mobs-per-wave-per-player", 0);
            config.set("waves.mobs.ZOMBIE.health-multiplier", 10);
            config.set("waves.health-increase-percent", 100);
            config.set("waves.health-increase-every-waves", 1);
        });
        startPlayers(4);
        spawns.completeWave();
        spawns.inWave(1).getFirst().assertHealth(580);
        tickAt(currentTick.get() + 1);
        spawns.completeWave();

        spawns.inWave(2).getFirst().assertHealth(1024);
        assertEquals(GameState.INTERMISSION, arena.getArenaState());
        verify(economy, never()).finish(any());
    }

    @Test void scaledWavesRespectConcurrentAndTotalMobCapsWithoutLosingQueuedSpawns() {
        DynamicSpawns spawns = configureScalingScenario(config -> {
            config.set("waves.max-active-mobs", 2);
            config.set("waves.max-mobs-per-wave", 7);
            config.set("waves.spawn-batch-size", 20);
        });
        startPlayers(4);
        assertEquals(2, arena.getActiveMobs().size());
        tickAt(currentTick.get() + 1);
        assertEquals(2, spawns.spawned.size(), "A full concurrent limit must leave the rest queued.");
        spawns.kill(spawns.spawned.getFirst());
        tickAt(currentTick.get() + 1);
        assertEquals(3, spawns.spawned.size());
        assertEquals(2, arena.getActiveMobs().size());
        spawns.completeWave();

        assertEquals(7, spawns.inWave(1).size());
        spawns.spawned.forEach(spawn -> spawn.assertHealth(29));
        assertTrue(sent(player).stream().anyMatch(message -> message.contains("Wave 1") && message.contains("7 mobs incoming")));
        verify(economy, times(1)).waveReward(1, 4);
    }

    @Test void nextMatchOnTheSameArenaResetsPlayerAndWaveScalingAndRejectsOldMobDeaths() {
        DynamicSpawns spawns = configureScalingScenario(config -> {});
        startPlayers(4);
        UUID oldSession = arena.getSessionId();
        spawns.completeWave();
        tickAt(currentTick.get() + 1);
        var oldMob = spawns.spawned.getLast();
        assertEquals(2, oldMob.wave);
        arena.endArena();
        assertEquals(GameState.WAITING, arena.getArenaState());
        assertEquals(0, arena.getStartingPlayerCount());
        assertTrue(spawns.live.isEmpty());
        UUID newSession = arena.getSessionId();
        assertNotEquals(oldSession, newSession);

        startPlayers(1);
        assertEquals(1, arena.getCurrentWave());
        assertEquals(1, arena.getStartingPlayerCount());
        var activeBeforeStaleDeath = arena.getActiveMobs();
        arena.mobDeath(oldMob.entity, player);
        assertEquals(activeBeforeStaleDeath, arena.getActiveMobs());
        assertEquals(0, arena.getKills(playerId));
        spawns.completeWave();

        var solo = spawns.spawned.stream().filter(spawn -> spawn.session.equals(newSession)).toList();
        assertEquals(6, solo.size());
        solo.forEach(spawn -> { assertEquals(1, spawn.wave); spawn.assertHealth(20); });
        verify(economy).waveReward(1, 1);
        ArgumentCaptor<SessionResult> results = ArgumentCaptor.forClass(SessionResult.class);
        verify(economy, times(1)).finish(results.capture());
        assertEquals(oldSession, results.getValue().sessionId());
        assertTrue(results.getValue().cancelled());
    }

    private DynamicSpawns configureScalingScenario(Consumer<YamlConfiguration> settings) {
        YamlConfiguration defaults = (YamlConfiguration) plugin.getConfig();
        defaults.set("waves.base-mobs", 5);
        defaults.set("waves.mobs-per-wave-per-player", 1);
        defaults.set("waves.spawn-batch-size", 1);
        defaults.set("waves.spawn-interval-ticks", 1);
        defaults.set("waves.intermission-seconds", 0);
        settings.accept(defaults);
        configureCountdown(0, 1, false);
        when(economy.waveReward(anyInt(), anyInt())).thenReturn(BigDecimal.ZERO);
        when(economy.capPot(any(BigDecimal.class))).thenAnswer(call -> call.getArgument(0));
        return new DynamicSpawns();
    }

    private List<Player> startPlayers(int count) {
        List<Player> starters = new ArrayList<>();
        starters.add(player);
        for (int index = 1; index < count; index++) starters.add(onlinePlayer("Starter" + index));
        starters.forEach(this::admit);
        tickAt(currentTick.get());
        assertEquals(GameState.ACTIVE, arena.getArenaState());
        assertEquals(count, arena.getStartingPlayerCount());
        return starters;
    }

    /** Opt-in entity registry for scaling tests; ordinary one-mob regressions retain their fixture. */
    private final class DynamicSpawns {
        private final List<SpawnedMob> spawned = new ArrayList<>();
        private final Map<UUID, SpawnedMob> live = new LinkedHashMap<>();

        private DynamicSpawns() {
            bukkit.when(() -> Bukkit.getEntity(any(UUID.class))).thenAnswer(call -> {
                SpawnedMob spawn = live.get(call.getArgument(0));
                return spawn == null ? null : spawn.entity;
            });
            when(chunk.getEntities()).thenAnswer(call -> live.values().stream().map(spawn -> spawn.entity).toArray(Entity[]::new));
            doAnswer(call -> {
                SpawnedMob spawn = new SpawnedMob(arena.getCurrentWave(), arena.getSessionId());
                Consumer<Zombie> initializer = call.getArgument(4);
                initializer.accept(spawn.entity);
                assertTrue(arena.owns(spawn.entity), "Each batch must initialize ownership before insertion.");
                spawned.add(spawn);
                live.put(spawn.entity.getUniqueId(), spawn);
                return spawn.entity;
            }).when(world).spawn(any(Location.class), eq(Zombie.class), eq(CreatureSpawnEvent.SpawnReason.CUSTOM), eq(false), any());
        }

        private List<SpawnedMob> inWave(int wave) {
            return spawned.stream().filter(spawn -> spawn.wave == wave).toList();
        }

        private void kill(SpawnedMob spawn) {
            assertNotNull(live.remove(spawn.entity.getUniqueId()));
            arena.mobDeath(spawn.entity, player);
        }

        private void completeWave() {
            int wave = arena.getCurrentWave();
            for (int attempt = 0; attempt < 1000 && arena.getArenaState() == GameState.ACTIVE; attempt++) {
                for (UUID id : arena.getActiveMobs()) {
                    SpawnedMob spawn = live.get(id);
                    assertNotNull(spawn, "Every tracked mob must exist in the fixture world.");
                    kill(spawn);
                }
                if (arena.getArenaState() == GameState.ACTIVE) tickAt(currentTick.get() + 1);
            }
            assertEquals(GameState.INTERMISSION, arena.getArenaState(), "The bounded wave drain must account for every queued spawn.");
            assertEquals(wave, arena.getCompletedWaves());
        }

        private final class SpawnedMob {
            private final Zombie entity = mock(Zombie.class);
            private final int wave;
            private final UUID session;
            private double maximumHealth = 20;
            private double currentHealth;

            private SpawnedMob(int wave, UUID session) {
                this.wave = wave;
                this.session = session;
                UUID id = UUID.randomUUID();
                when(entity.getUniqueId()).thenReturn(id);
                when(entity.getType()).thenReturn(EntityType.ZOMBIE);
                when(entity.getLocation()).thenReturn(arena.definition().mobSpawns.getFirst());
                when(entity.isValid()).thenReturn(true);
                AttributeInstance health = mock(AttributeInstance.class);
                when(health.getBaseValue()).thenAnswer(call -> maximumHealth);
                doAnswer(call -> { maximumHealth = call.getArgument(0); return null; }).when(health).setBaseValue(anyDouble());
                when(entity.getAttribute(Attribute.MAX_HEALTH)).thenReturn(health);
                doAnswer(call -> { currentHealth = call.getArgument(0); return null; }).when(entity).setHealth(anyDouble());
                PersistentDataContainer data = mock(PersistentDataContainer.class);
                AtomicReference<String> tag = new AtomicReference<>();
                NamespacedKey key = plugin.sessionKey();
                when(entity.getPersistentDataContainer()).thenReturn(data);
                doAnswer(call -> { tag.set(call.getArgument(2)); return null; }).when(data).set(eq(key), eq(PersistentDataType.STRING), anyString());
                when(data.get(key, PersistentDataType.STRING)).thenAnswer(call -> tag.get());
                doAnswer(call -> { live.remove(id); return null; }).when(entity).remove();
            }

            private void assertHealth(double expected) {
                assertEquals(expected, maximumHealth, 1.0e-9, "Maximum health in wave " + wave);
                assertEquals(expected, currentHealth, 1.0e-9, "Initial current health must match maximum health in wave " + wave);
            }
        }
    }

    private void configureCountdown(int seconds, int minimumPlayers, boolean publicMessages) {
        for (String event : List.of("join", "countdown", "start", "end"))
            plugin.getConfig().set("announcements.public." + event, publicMessages);
        YamlConfiguration config = arena.getConfiguration();
        config.set("countdown-seconds", seconds);
        config.set("min-players", minimumPlayers);
        ArenaDefinition definition = new ArenaDefinition("spawn", config, plugin.getConfig());
        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        arena = new Arena(plugin, definition);
    }

    private void configureWaveWithPendingMob() {
        plugin.getConfig().set("waves.base-mobs", 2);
        plugin.getConfig().set("waves.spawn-batch-size", 1);
        configureCountdown(0, 1, false);
    }

    private Zombie taggedMob(UUID id, UUID session) {
        Zombie entity = mock(Zombie.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(entity.getUniqueId()).thenReturn(id);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        NamespacedKey key = plugin.sessionKey();
        when(data.get(key, PersistentDataType.STRING)).thenReturn(session.toString());
        return entity;
    }

    private static void assertNoKillCue(Player recipient) {
        verify(recipient, never()).playSound(any(Location.class), eq("minecraft:entity.experience_orb.pickup"), eq(SoundCategory.MASTER), eq(.4F), eq(1.7F));
    }

    private Player onlinePlayer(String name) {
        Player additional = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(additional.getUniqueId()).thenReturn(id);
        when(additional.getName()).thenReturn(name);
        when(additional.isOnline()).thenReturn(true);
        when(additional.getLocation()).thenReturn(arena.definition().playerSpawns.getFirst());
        when(additional.teleport(any(Location.class))).thenReturn(true);
        AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
        when(additional.getAttribute(Attribute.MAX_HEALTH)).thenReturn(health);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(additional);
        onlinePlayers.add(additional);
        return additional;
    }

    private void admit(Player joining) { arena.reserve(); arena.joined(joining); }
    private void tickAt(long tick) { currentTick.set(tick); arena.tick(tick); }

    private static List<String> sent(Player recipient) {
        return mockingDetails(recipient).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("sendMessage") && call.getArguments().length == 1
                && call.getArgument(0) instanceof Component)
            .map(call -> plain(call.getArgument(0))).toList();
    }

    private static List<Integer> countdownNotices(Player recipient) {
        java.util.regex.Pattern countdown = java.util.regex.Pattern.compile("STARTING IN (\\d+)s");
        return titles(recipient).stream().map(countdown::matcher).filter(java.util.regex.Matcher::matches)
            .map(match -> Integer.parseInt(match.group(1))).toList();
    }

    private static List<String> titles(Player recipient) {
        return mockingDetails(recipient).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("showTitle") && call.getArguments().length == 1)
            .map(call -> plain(((Title) call.getArgument(0)).title())).toList();
    }

    private static void assertNoPrivateCountdownFor(Player observer) {
        verify(observer, never()).showTitle(any(Title.class));
        assertTrue(sent(observer).stream().noneMatch(message -> message.contains("starts in") || message.contains("Waiting for players in")));
    }

    @SuppressWarnings("unchecked")
    private void ageMatchBySeconds(long seconds) throws Exception {
        Instant started = Instant.now().minusSeconds(seconds);
        var startedAt = Arena.class.getDeclaredField("startedAt");
        startedAt.setAccessible(true);
        startedAt.set(arena, started);
        var participants = Arena.class.getDeclaredField("participants");
        participants.setAccessible(true);
        ((Map<UUID, Participation>) participants.get(arena)).values().forEach(participant -> participant.start(started));
    }

    private void assertRejectedSpawn() {
        UUID session = arena.getSessionId();
        beginFirstWave();
        assertCancelledAndRecovered();
        assertTrue(spawnEvent.isCancelled(), "Protection cancellation must remain in force.");
        assertFalse(addedToWorld.get());
        verify(mob).remove();
        verify(player).playSound(any(Location.class), eq("minecraft:block.note_block.didgeridoo"), eq(SoundCategory.MASTER), anyFloat(), anyFloat());
        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
        verify(logger).log(eq(Level.WARNING), contains(session.toString()), failure.capture());
        assertTrue(failure.getValue().getMessage().contains("ZOMBIE"));
        assertTrue(failure.getValue().getMessage().contains("world,4.5,64.0,4.5"));
        assertTrue(messages().stream().anyMatch(message -> message.contains("WorldGuard mob-spawning / deny-spawn")
            && message.contains("region priorities") && message.contains("without payout")));
    }

    private void assertCancelledAndRecovered() {
        ArgumentCaptor<SessionResult> result = ArgumentCaptor.forClass(SessionResult.class);
        verify(economy).finish(result.capture());
        assertTrue(result.getValue().cancelled());
        assertEquals(0, result.getValue().completedWaves());
        assertEquals(BigDecimal.ZERO, result.getValue().prizePot());
        assertEquals(playerId, result.getValue().participants().getFirst().playerId());
        assertEquals(0, result.getValue().participants().getFirst().kills());
        verify(economy, never()).waveReward(anyInt(), anyInt());
        verify(equipment).restore(eq(player), eq(arena.definition().exit), any());
        verify(manager).release(playerId);
        verify(manager).cancelReservations(arena);
        verify(chunk).removePluginChunkTicket(plugin);
        assertEquals(GameState.WAITING, arena.getArenaState());
        assertTrue(arena.activeIds().isEmpty());
        assertTrue(arena.getActiveMobs().isEmpty());
    }

    private void beginFirstWave() {
        arena.reserve();
        arena.joined(player);
        assertEquals(GameState.COUNTDOWN, arena.getArenaState());
        arena.tick(1);
    }

    private List<String> messages() {
        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(messages.capture());
        return messages.getAllValues().stream().map(ArenaSpawnTest::plain).toList();
    }

    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

    private record TestAttribute(Key key) implements Attribute {
        @Override public NamespacedKey getKey() { return new NamespacedKey(key.namespace(), key.value()); }
        @Override public String getTranslationKey() { return "attribute.name." + key.value(); }
        @Override public String translationKey() { return getTranslationKey(); }
        @Override public Sentiment getSentiment() { return Sentiment.POSITIVE; }
        @Override public double getDefaultValue() { return 20; }
        @Override public int compareTo(Attribute other) { return getKey().toString().compareTo(other.getKey().toString()); }
        @Override public String name() { return key.value(); }
        @Override public int ordinal() { return 0; }
    }
}
