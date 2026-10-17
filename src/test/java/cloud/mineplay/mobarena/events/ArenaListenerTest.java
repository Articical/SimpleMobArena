package cloud.mineplay.mobarena.events;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.arenas.ArenaManager;
import cloud.mineplay.mobarena.config.ArenaDefinition;
import cloud.mineplay.mobarena.enums.GameState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArenaListenerTest {
    private SimpleMobArena plugin;
    private ArenaManager manager;
    private Arena arena;
    private Player player;
    private UUID playerId;
    private ArenaListener listener;

    @BeforeEach void setup() {
        plugin = mock(SimpleMobArena.class);
        manager = mock(ArenaManager.class);
        arena = mock(Arena.class);
        player = mock(Player.class);
        playerId = UUID.randomUUID();
        when(plugin.getArenaManager()).thenReturn(manager);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getHealth()).thenReturn(4.0);
        when(manager.getPlayerArena(playerId)).thenReturn(arena);
        when(arena.isParticipant(playerId)).thenReturn(true);
        when(arena.getArenaState()).thenReturn(GameState.ACTIVE);
        when(arena.contains(any())).thenReturn(true);
        listener = new ArenaListener(plugin);
    }

    private EntityDamageEvent environmental(double damage) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getFinalDamage()).thenReturn(damage);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(event.isCancelled()).thenAnswer(call -> cancelled.get());
        doAnswer(call -> { cancelled.set(call.getArgument(0)); return null; }).when(event).setCancelled(anyBoolean());
        return event;
    }

    private EntityDamageByEntityEvent hit(Entity attacker, Entity victim, double damage) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getFinalDamage()).thenReturn(damage);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(event.isCancelled()).thenAnswer(call -> cancelled.get());
        doAnswer(call -> { cancelled.set(call.getArgument(0)); return null; }).when(event).setCancelled(anyBoolean());
        return event;
    }

    @Test void lethalEnvironmentDamageIsCancelledAndDuplicateEventCannotEliminateAgain() {
        doAnswer(call -> { when(manager.getPlayerArena(playerId)).thenReturn(null); return null; })
                .when(arena).eliminate(eq(player),anyString());
        EntityDamageEvent first = environmental(4);
        listener.damage(first);
        assertTrue(first.isCancelled());
        listener.damage(environmental(100));
        verify(arena,times(1)).eliminate(player,"was eliminated");
    }

    @Test void nonlethalArenaCombatKeepsNormalDamage() {
        EntityDamageEvent event = environmental(3);
        listener.damage(event);
        assertFalse(event.isCancelled());
        verify(arena,never()).eliminate(any(),anyString());
    }

    @Test void normalSurvivalPlayersRemainOutsideArenaDamageRules() {
        when(manager.getPlayerArena(playerId)).thenReturn(null);
        EntityDamageEvent event = environmental(100);
        listener.damage(event);
        assertFalse(event.isCancelled());
        verify(arena,never()).eliminate(any(),anyString());
    }

    @Test void outsiderCannotDamageOrEliminateAnArenaParticipant() {
        Player outsider = mock(Player.class);
        when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityDamageByEntityEvent event = hit(outsider,player,100);
        listener.damage(event);
        assertTrue(event.isCancelled());
        verify(arena,never()).eliminate(any(),anyString());
    }

    @Test void onlyRegisteredSessionMobsCanInflictLethalCombatDamage() {
        Zombie zombie = mock(Zombie.class);
        UUID mobId = UUID.randomUUID();
        when(zombie.getUniqueId()).thenReturn(mobId);
        when(manager.owner(zombie)).thenReturn(arena);
        when(arena.getActiveMobs()).thenReturn(Set.of(mobId));
        EntityDamageByEntityEvent event = hit(zombie,player,4);
        listener.damage(event);
        assertTrue(event.isCancelled());
        verify(arena).eliminate(player,"was eliminated");
    }

    @Test void registeredTagWithoutTrackedMobIdentityCannotDamageParticipants() {
        Zombie unregistered = mock(Zombie.class);
        when(unregistered.getUniqueId()).thenReturn(UUID.randomUUID());
        when(manager.owner(unregistered)).thenReturn(arena);
        when(arena.getActiveMobs()).thenReturn(Set.of());
        EntityDamageByEntityEvent event = hit(unregistered,player,4);
        listener.damage(event);
        assertTrue(event.isCancelled());
        verify(arena,never()).eliminate(any(),anyString());
    }

    @Test void departedPlayersInFlightArrowCannotDamageSurvivalPlayers() {
        when(manager.getPlayerArena(playerId)).thenReturn(null);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(player);
        when(manager.owner(arrow)).thenReturn(arena);
        Player outsider = mock(Player.class);
        when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityDamageByEntityEvent event = hit(arrow,outsider,8);
        listener.damage(event);
        assertTrue(event.isCancelled());
    }

    @Test void arenaParticipantCannotAttackUnrelatedSurvivalMob() {
        Zombie survivalMob = mock(Zombie.class);
        EntityDamageByEntityEvent event = hit(player,survivalMob,8);
        listener.damage(event);
        assertTrue(event.isCancelled());
    }

    @Test void ownedZombieDrownedAndSkeletonFamiliesCannotIgniteFromSunlight() {
        for (Class<? extends LivingEntity> type : List.of(Zombie.class, Drowned.class, Husk.class,
                Skeleton.class, Stray.class, WitherSkeleton.class)) {
            LivingEntity mob = mock(type);
            when(manager.owner(mob)).thenReturn(arena);
            EntityCombustEvent event = new EntityCombustEvent(mob, 8.0F);

            listener.sunlight(event);

            assertTrue(event.isCancelled(), type.getSimpleName() + " sunlight must be blocked");
            verify(mob).setFireTicks(0);
        }
    }

    @Test void unownedUndeadKeepNormalSunlightBehavior() {
        for (Class<? extends LivingEntity> type : List.of(Zombie.class, Drowned.class, Skeleton.class)) {
            LivingEntity mob = mock(type);
            EntityCombustEvent event = new EntityCombustEvent(mob, 8.0F);

            listener.sunlight(event);

            assertFalse(event.isCancelled());
            verify(mob, never()).setFireTicks(anyInt());
        }
    }

    @Test void playersAndOtherArenaEntitiesAreNotGivenSunlightCombustionImmunity() {
        for (LivingEntity entity : List.of(player, mock(Chicken.class), mock(Spider.class))) {
            when(manager.owner(entity)).thenReturn(arena);
            EntityCombustEvent event = new EntityCombustEvent(entity, 8.0F);

            listener.sunlight(event);

            assertFalse(event.isCancelled());
            verify(entity, never()).setFireTicks(anyInt());
        }
    }

    @Test void ownedUndeadRetainBlockAndEntityIgnition() {
        for (Class<? extends LivingEntity> type : List.of(Zombie.class, Drowned.class, Skeleton.class)) {
            LivingEntity mob = mock(type);
            when(manager.owner(mob)).thenReturn(arena);
            // A missing block source is legal for combustion; its event subtype still matters.
            EntityCombustByBlockEvent blockFire = new EntityCombustByBlockEvent(null, mob, 8.0F);
            EntityCombustByEntityEvent attackFire = new EntityCombustByEntityEvent(player, mob, 8.0F);

            listener.sunlight(blockFire);
            listener.sunlight(attackFire);

            assertFalse(blockFire.isCancelled());
            assertFalse(attackFire.isCancelled());
            verify(mob, never()).setFireTicks(anyInt());
        }
    }

    @Test void unrelatedMobsCannotSpawnInIdleOrOccupiedEnabledArenas() {
        Location location = new Location(mock(World.class), 5, 65, 5);
        when(manager.at(location)).thenReturn(arena);
        for (boolean occupied : List.of(false, true)) {
            when(manager.occupied(arena)).thenReturn(occupied);
            for (CreatureSpawnEvent.SpawnReason reason : List.of(CreatureSpawnEvent.SpawnReason.NATURAL,
                    CreatureSpawnEvent.SpawnReason.CUSTOM, CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
                    CreatureSpawnEvent.SpawnReason.EGG, CreatureSpawnEvent.SpawnReason.SPAWNER)) {
                Zombie mob = spawnMob(Zombie.class, location, null);
                CreatureSpawnEvent event = new CreatureSpawnEvent(mob, reason);

                listener.spawn(event);

                assertTrue(event.isCancelled(), reason + " must be blocked with occupied=" + occupied);
                verify(mob, never()).remove();
            }
        }
    }

    @Test void ownCurrentSessionMobsAndJockeyMountsCanSpawnWithoutOverridingOtherPlugins() {
        Location location = new Location(mock(World.class), 5, 65, 5);
        Arena destination = new Arena(plugin, mock(ArenaDefinition.class));
        when(manager.at(location)).thenReturn(destination);
        when(manager.occupied(destination)).thenReturn(true);
        for (Class<? extends Mob> type : List.of(Zombie.class, Chicken.class)) {
            Mob mob = spawnMob(type, location, destination.getSessionId().toString());
            CreatureSpawnEvent event = new CreatureSpawnEvent(mob, CreatureSpawnEvent.SpawnReason.CUSTOM);

            listener.spawn(event);

            assertFalse(event.isCancelled(), type.getSimpleName() + " belongs to this match");
            event.setCancelled(true);
            listener.spawn(event);
            assertTrue(event.isCancelled(), "Another plugin's cancellation must remain in force");
        }
    }

    @Test void staleOrOtherArenaSessionTagsCannotBypassSpawnProtection() {
        Location location = new Location(mock(World.class), 5, 65, 5);
        Arena destination = new Arena(plugin, mock(ArenaDefinition.class));
        Arena otherArena = new Arena(plugin, mock(ArenaDefinition.class));
        when(manager.at(location)).thenReturn(destination);
        when(manager.occupied(destination)).thenReturn(true);
        for (String session : List.of(UUID.randomUUID().toString(), otherArena.getSessionId().toString())) {
            Zombie mob = spawnMob(Zombie.class, location, session);
            CreatureSpawnEvent event = new CreatureSpawnEvent(mob, CreatureSpawnEvent.SpawnReason.CUSTOM);

            listener.spawn(event);

            assertTrue(event.isCancelled());
        }
    }

    @Test void spawnsOutsideEnabledArenaBoundariesKeepNormalBehavior() {
        // ArenaManager.at returns null both outside every arena and inside a disabled arena.
        Location location = new Location(mock(World.class), 50, 65, 50);
        for (CreatureSpawnEvent.SpawnReason reason : List.of(CreatureSpawnEvent.SpawnReason.NATURAL,
                CreatureSpawnEvent.SpawnReason.CUSTOM, CreatureSpawnEvent.SpawnReason.SPAWNER_EGG)) {
            Zombie mob = spawnMob(Zombie.class, location, null);
            CreatureSpawnEvent event = new CreatureSpawnEvent(mob, reason);

            listener.spawn(event);

            assertFalse(event.isCancelled());
            verify(mob, never()).remove();
        }
    }

    private <T extends Mob> T spawnMob(Class<T> type, Location location, String session) {
        T mob = mock(type);
        NamespacedKey key = new NamespacedKey("simplemobarena", "session");
        when(plugin.sessionKey()).thenReturn(key);
        when(mob.getLocation()).thenReturn(location);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(mob.getPersistentDataContainer()).thenReturn(data);
        when(data.get(key, PersistentDataType.STRING)).thenReturn(session);
        when(data.has(key, PersistentDataType.STRING)).thenReturn(session != null);
        return mob;
    }

    @Test void arenaSpawnProtectionDoesNotBlockDecorativeArmorStands() {
        Location location = new Location(mock(World.class), 5, 65, 5);
        when(manager.at(location)).thenReturn(arena);
        ArmorStand decoration = mock(ArmorStand.class);
        when(decoration.getLocation()).thenReturn(location);
        CreatureSpawnEvent event = new CreatureSpawnEvent(decoration, CreatureSpawnEvent.SpawnReason.CUSTOM);

        listener.spawn(event);

        assertFalse(event.isCancelled());
    }

    @Test void voluntaryDisconnectIsDelegatedToTheSameLeaveFlow() {
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);
        listener.quit(event);
        verify(manager).disconnected(player);
    }

    @Test void commandWhitelistRequiresResolvedPluginOwnershipAndRejectsAdminActions() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            PluginCommand own = mock(PluginCommand.class);
            when(own.getPlugin()).thenReturn(plugin);
            bukkit.when(() -> Bukkit.getPluginCommand("ma")).thenReturn(own);
            PlayerCommandPreprocessEvent leave = new PlayerCommandPreprocessEvent(player,"/ma leave",Set.of());
            listener.commands(leave);
            assertFalse(leave.isCancelled());
            PlayerCommandPreprocessEvent admin = new PlayerCommandPreprocessEvent(player,"/ma admin stop spawn",Set.of());
            listener.commands(admin);
            assertTrue(admin.isCancelled());
            PluginCommand collision = mock(PluginCommand.class);
            when(collision.getPlugin()).thenReturn(mock(Plugin.class));
            bukkit.when(() -> Bukkit.getPluginCommand("ma")).thenReturn(collision);
            PlayerCommandPreprocessEvent borrowedAlias = new PlayerCommandPreprocessEvent(player,"/ma leave",Set.of());
            listener.commands(borrowedAlias);
            assertTrue(borrowedAlias.isCancelled());
        }
    }

    @Test void armorStandInteractionAndFishingCannotTransferClassItems() {
        PlayerArmorStandManipulateEvent armorStand = mock(PlayerArmorStandManipulateEvent.class);
        when(armorStand.getPlayer()).thenReturn(player);
        listener.armorStand(armorStand);
        verify(armorStand).setCancelled(true);
        PlayerFishEvent fish = mock(PlayerFishEvent.class);
        when(fish.getPlayer()).thenReturn(player);
        listener.fish(fish);
        verify(fish).setCancelled(true);
    }

    @Test void tridentReleaseIsCancelledEvenWhenChargingStartedBeforeArenaEntry() {
        Trident trident = mock(Trident.class);
        when(trident.getShooter()).thenReturn(player);
        ProjectileLaunchEvent event = mock(ProjectileLaunchEvent.class);
        when(event.getEntity()).thenReturn(trident);
        listener.projectile(event);
        verify(event).setCancelled(true);
        verify(arena,never()).tag(trident);
    }

    @Test void naturalTrackedCreeperExplosionCompletesMobWithoutPlayerKillCredit() {
        Creeper creeper = mock(Creeper.class);
        UUID mobId = UUID.randomUUID();
        when(creeper.getUniqueId()).thenReturn(mobId);
        when(manager.owner(creeper)).thenReturn(arena);
        when(arena.getActiveMobs()).thenReturn(Set.of(mobId));
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        when(event.getEntity()).thenReturn(creeper);
        listener.exploded(event);
        verify(arena).mobDeath(creeper,null);
    }

    @Test void taggedChickenCannotLayEconomyItems() {
        Chicken chicken = mock(Chicken.class);
        when(manager.owner(chicken)).thenReturn(arena);
        EntityDropItemEvent event = mock(EntityDropItemEvent.class);
        when(event.getEntity()).thenReturn(chicken);
        listener.entityDrop(event);
        verify(event).setCancelled(true);
    }

    @Test void bundleCannotHideAmmunitionFromConsumableRestoration() {
        ItemStack bundle = mock(ItemStack.class);
        when(bundle.getItemMeta()).thenReturn(mock(BundleMeta.class));
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getCurrentItem()).thenReturn(bundle);
        when(event.getAction()).thenReturn(InventoryAction.PICKUP_ALL);
        listener.click(event);
        verify(event).setCancelled(true);
    }
}
