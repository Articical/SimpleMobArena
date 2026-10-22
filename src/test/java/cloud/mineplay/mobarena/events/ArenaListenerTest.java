package cloud.mineplay.mobarena.events;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.arenas.ArenaManager;
import cloud.mineplay.mobarena.config.ArenaDefinition;
import cloud.mineplay.mobarena.enums.GameState;
import io.papermc.paper.registry.RegistryAccess;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
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
    private Server server;

    @BeforeAll static void initializePotionEffectIdentities() {
        try (MockedStatic<RegistryAccess> registryAccess = mockStatic(RegistryAccess.class)) {
            RegistryAccess access = mock(RegistryAccess.class, RETURNS_DEEP_STUBS);
            registryAccess.when(RegistryAccess::registryAccess).thenReturn(access);
            Registry<PotionEffectType> effects = Registry.MOB_EFFECT;
            doAnswer(call -> new TestEffectType(call.getArgument(0)))
                    .when(effects).getOrThrow(any(Key.class));
            assertNotNull(PotionEffectType.INSTANT_HEALTH);
        }
    }

    @BeforeEach void setup() {
        plugin = mock(SimpleMobArena.class);
        manager = mock(ArenaManager.class);
        arena = mock(Arena.class);
        player = mock(Player.class);
        playerId = UUID.randomUUID();
        when(plugin.getArenaManager()).thenReturn(manager);
        server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getHealth()).thenReturn(4.0);
        when(manager.getPlayerArena(playerId)).thenReturn(arena);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(arena.isParticipant(playerId)).thenReturn(true);
        when(arena.getArenaState()).thenReturn(GameState.ACTIVE);
        when(arena.isCombatMob(any())).thenAnswer(call -> arena.getActiveMobs().contains(call.getArgument(0)));
        when(arena.contains(any())).thenReturn(true);
        when(arena.getSessionId()).thenReturn(UUID.randomUUID());
        listener = new ArenaListener(plugin);
    }

    private Player anotherParticipant(Arena match) {
        Player other = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(other.getUniqueId()).thenReturn(id);
        when(other.getHealth()).thenReturn(20.0);
        when(manager.getPlayerArena(id)).thenReturn(match);
        when(server.getPlayer(id)).thenReturn(other);
        when(match.isParticipant(id)).thenReturn(true);
        when(match.contains(any())).thenReturn(true);
        when(match.getArenaState()).thenReturn(GameState.ACTIVE);
        return other;
    }

    private Player duelOpponent() {
        when(arena.isDuel()).thenReturn(true);
        Player opponent = anotherParticipant(arena);
        when(arena.opponents(opponent.getUniqueId(),playerId)).thenReturn(true);
        when(arena.opponents(playerId,opponent.getUniqueId())).thenReturn(true);
        return opponent;
    }

    private void damageAndMonitor(EntityDamageByEntityEvent event) {
        // Bukkit skips both ignoreCancelled handlers after another plugin cancels the hit.
        if (!event.isCancelled()) listener.damage(event);
        if (!event.isCancelled()) listener.damageStatistic(event);
    }

    private Arrow arrow(Player shooter, Arena owner) {
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(shooter);
        when(manager.owner(arrow)).thenReturn(owner);
        return arrow;
    }

    private PotionSplashEvent splash(ProjectileSource shooter, Arena owner, boolean harmful, LivingEntity... targets) {
        ThrownPotion potion = mock(ThrownPotion.class);
        when(potion.getUniqueId()).thenReturn(UUID.randomUUID());
        when(potion.getShooter()).thenReturn(shooter);
        when(manager.owner(potion)).thenReturn(owner);
        PotionEffectType type = mock(PotionEffectType.class);
        when(type.getEffectCategory()).thenReturn(harmful ? PotionEffectType.Category.HARMFUL : PotionEffectType.Category.BENEFICIAL);
        PotionEffect effect = mock(PotionEffect.class);
        when(effect.getType()).thenReturn(type);
        when(potion.getEffects()).thenReturn(List.of(effect));
        PotionSplashEvent event = mock(PotionSplashEvent.class);
        when(event.getPotion()).thenReturn(potion);
        when(event.getAffectedEntities()).thenReturn(List.of(targets));
        return event;
    }

    private PotionSplashEvent damagingSplash(Player attacker, PotionEffect effect) {
        PotionSplashEvent event = splash(attacker,arena,true,player);
        ThrownPotion potion = event.getPotion();
        when(potion.getEffects()).thenReturn(List.of(effect));
        when(event.getIntensity(player)).thenReturn(1.0);
        return event;
    }

    private EntityPotionEffectEvent changedEffect(Entity source, PotionEffect effect) {
        EntityPotionEffectEvent event = mock(EntityPotionEffectEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getSource()).thenReturn(source);
        when(event.getModifiedType()).thenReturn(effect.getType());
        when(event.getNewEffect()).thenReturn(effect);
        when(event.getCause()).thenReturn(EntityPotionEffectEvent.Cause.POTION_SPLASH);
        when(event.getAction()).thenReturn(EntityPotionEffectEvent.Action.ADDED);
        when(event.isOverride()).thenReturn(true);
        return event;
    }

    private void applyDamagingPotion(Player attacker, PotionEffect effect) {
        listener.appliedSplash(damagingSplash(attacker,effect));
        listener.potionEffect(changedEffect(attacker,effect));
        when(player.getPotionEffect(effect.getType())).thenReturn(effect);
    }

    private EntityDamageEvent damageOverTime(EntityDamageEvent.DamageCause cause, double amount) {
        EntityDamageEvent event = environmental(amount);
        when(event.getCause()).thenReturn(cause);
        listener.damage(event);
        if (!event.isCancelled()) listener.damageOverTimeStatistic(event);
        return event;
    }

    @Test void appliedPoisonDamageIsCreditedOnlyAfterItsUncancelledDamageTick() {
        Player attacker = duelOpponent();
        PotionEffect poison = new PotionEffect(PotionEffectType.POISON,200,0);
        applyDamagingPotion(attacker,poison);
        EntityDamageEvent event = damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        assertFalse(event.isCancelled());
        verify(arena).duelHit(attacker.getUniqueId(),playerId,1);

        EntityDamageEvent cancelled = environmental(1);
        when(cancelled.getCause()).thenReturn(EntityDamageEvent.DamageCause.POISON);
        cancelled.setCancelled(true);
        listener.damageOverTimeStatistic(cancelled);
        verify(arena,times(1)).duelHit(any(),any(),anyDouble());
    }

    @Test void lethalWitherTickCreditsRemainingHealthBeforeEliminationOnce() {
        Player attacker = duelOpponent();
        applyDamagingPotion(attacker,new PotionEffect(PotionEffectType.WITHER,200,0));
        EntityDamageEvent event = damageOverTime(EntityDamageEvent.DamageCause.WITHER,100);
        assertTrue(event.isCancelled());
        var order = inOrder(arena);
        order.verify(arena).duelHit(attacker.getUniqueId(),playerId,4);
        order.verify(arena).eliminate(player,"was eliminated");
        verify(arena,times(1)).duelHit(any(),any(),anyDouble());
    }

    @Test void splashCandidatesDoNotCreditEffectsThatWereCancelledOrNeverApplied() {
        Player attacker = duelOpponent();
        PotionEffect poison = new PotionEffect(PotionEffectType.POISON,200,0);
        listener.appliedSplash(damagingSplash(attacker,poison));
        when(player.getPotionEffect(PotionEffectType.POISON)).thenReturn(poison);
        damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        EntityPotionEffectEvent rejected = changedEffect(attacker,poison);
        when(rejected.isCancelled()).thenReturn(true);
        listener.potionEffect(rejected);
        damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        verify(arena,never()).duelHit(any(),any(),anyDouble());
    }

    @Test void cancelledAndZeroIntensitySplashesCannotEstablishDamageCredit() {
        Player attacker = duelOpponent();
        PotionEffect poison = new PotionEffect(PotionEffectType.POISON,200,0);
        for (boolean cancelled : List.of(false,true)) {
            PotionSplashEvent splash = damagingSplash(attacker,poison);
            when(splash.isCancelled()).thenReturn(cancelled);
            when(splash.getIntensity(player)).thenReturn(cancelled ? 1.0 : 0.0);
            listener.appliedSplash(splash);
            listener.potionEffect(changedEffect(attacker,poison));
            when(player.getPotionEffect(PotionEffectType.POISON)).thenReturn(poison);
            damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        }
        verify(arena,never()).duelHit(any(),any(),anyDouble());
    }

    @Test void effectRemovalAndNonSplashReplacementClearAttribution() {
        Player attacker = duelOpponent();
        PotionEffect poison = new PotionEffect(PotionEffectType.POISON,200,0);
        for (EntityPotionEffectEvent.Action action : List.of(EntityPotionEffectEvent.Action.REMOVED,
                EntityPotionEffectEvent.Action.CLEARED,EntityPotionEffectEvent.Action.CHANGED)) {
            applyDamagingPotion(attacker,poison);
            EntityPotionEffectEvent replacement = changedEffect(null,poison);
            when(replacement.getAction()).thenReturn(action);
            when(replacement.getCause()).thenReturn(EntityPotionEffectEvent.Cause.PLUGIN);
            if (action != EntityPotionEffectEvent.Action.CHANGED) when(replacement.getNewEffect()).thenReturn(null);
            listener.potionEffect(replacement);
            damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        }
        verify(arena,never()).duelHit(any(),any(),anyDouble());
    }

    @Test void refusedWeakerReplacementKeepsCreditWithTheActiveStrongerPotion() {
        Player original = duelOpponent();
        Player second = duelOpponent();
        PotionEffect strong = new PotionEffect(PotionEffectType.POISON,200,1);
        applyDamagingPotion(original,strong);
        PotionEffect weak = new PotionEffect(PotionEffectType.POISON,100,0);
        listener.appliedSplash(damagingSplash(second,weak));
        EntityPotionEffectEvent refused = changedEffect(second,weak);
        when(refused.getAction()).thenReturn(EntityPotionEffectEvent.Action.CHANGED);
        when(refused.isOverride()).thenReturn(false);
        listener.potionEffect(refused);
        damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        verify(arena).duelHit(original.getUniqueId(),playerId,1);
        verify(arena,never()).duelHit(eq(second.getUniqueId()),any(),anyDouble());
    }

    @Test void acceptedReplacementTransfersCreditToItsActualThrower() {
        Player original = duelOpponent();
        Player second = duelOpponent();
        applyDamagingPotion(original,new PotionEffect(PotionEffectType.POISON,200,0));
        PotionEffect strong = new PotionEffect(PotionEffectType.POISON,200,1);
        listener.appliedSplash(damagingSplash(second,strong));
        EntityPotionEffectEvent accepted = changedEffect(second,strong);
        when(accepted.getAction()).thenReturn(EntityPotionEffectEvent.Action.CHANGED);
        listener.potionEffect(accepted);
        when(player.getPotionEffect(PotionEffectType.POISON)).thenReturn(strong);
        damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        verify(arena).duelHit(second.getUniqueId(),playerId,1);
        verify(arena,never()).duelHit(eq(original.getUniqueId()),any(),anyDouble());
    }

    @Test void missingExpiredOrUnexpectedlyExtendedEffectsCannotKeepAttribution() {
        Player attacker = duelOpponent();
        PotionEffect poison = new PotionEffect(PotionEffectType.POISON,200,0);
        for (PotionEffect current : new PotionEffect[] {null,new PotionEffect(PotionEffectType.POISON,0,0),
                new PotionEffect(PotionEffectType.POISON,400,0),new PotionEffect(PotionEffectType.POISON,100,1)}) {
            applyDamagingPotion(attacker,poison);
            when(player.getPotionEffect(PotionEffectType.POISON)).thenReturn(current);
            damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        }
        applyDamagingPotion(attacker,poison);
        when(plugin.ticks()).thenReturn(200L);
        listener.tick();
        damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        verify(arena,never()).duelHit(any(),any(),anyDouble());
    }

    @Test void expiredSplashCandidateAndMismatchedEffectSourceCannotClaimCredit() {
        Player attacker = duelOpponent();
        Player other = duelOpponent();
        PotionEffect poison = new PotionEffect(PotionEffectType.POISON,200,0);
        listener.appliedSplash(damagingSplash(attacker,poison));
        listener.potionEffect(changedEffect(other,poison));
        when(player.getPotionEffect(PotionEffectType.POISON)).thenReturn(poison);
        damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        listener.appliedSplash(damagingSplash(attacker,poison));
        when(plugin.ticks()).thenReturn(1L);
        listener.potionEffect(changedEffect(attacker,poison));
        damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        verify(arena,never()).duelHit(any(),any(),anyDouble());
    }

    @Test void paperCanReportTheTaggedPotionItselfAsTheAppliedEffectSource() {
        Player attacker = duelOpponent();
        PotionEffect poison = new PotionEffect(PotionEffectType.POISON,200,0);
        PotionSplashEvent splash = damagingSplash(attacker,poison);
        listener.appliedSplash(splash);
        listener.potionEffect(changedEffect(splash.getPotion(),poison));
        when(player.getPotionEffect(PotionEffectType.POISON)).thenReturn(poison);
        damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        verify(arena).duelHit(attacker.getUniqueId(),playerId,1);
    }

    @Test void departureAndSessionRolloverCannotCarryPotionCreditIntoAnotherMatch() {
        Player attacker = duelOpponent();
        PotionEffect poison = new PotionEffect(PotionEffectType.POISON,200,0);
        applyDamagingPotion(attacker,poison);
        when(manager.getPlayerArena(attacker.getUniqueId())).thenReturn(null);
        listener.tick();
        when(manager.getPlayerArena(attacker.getUniqueId())).thenReturn(arena);
        damageOverTime(EntityDamageEvent.DamageCause.POISON,1);

        applyDamagingPotion(attacker,poison);
        when(arena.getSessionId()).thenReturn(UUID.randomUUID());
        damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        verify(arena,never()).duelHit(any(),any(),anyDouble());
    }

    @Test void finishedArenaImmediatelyDropsBothActiveEffectsAndPendingApplications() {
        Player attacker = duelOpponent();
        PotionEffect poison = new PotionEffect(PotionEffectType.POISON,200,0);
        applyDamagingPotion(attacker,poison);
        listener.appliedSplash(damagingSplash(attacker,poison));
        listener.finished(new cloud.mineplay.mobarena.cevents.ArenaEndEvent(arena));
        listener.potionEffect(changedEffect(attacker,poison));
        damageOverTime(EntityDamageEvent.DamageCause.POISON,1);
        verify(arena,never()).duelHit(any(),any(),anyDouble());
    }

    @Test void activeDuelOpponentsCanDamageEachOtherAndOnlyActualDamageIsRecorded() {
        Player attacker = duelOpponent();
        EntityDamageByEntityEvent event = hit(attacker,player,3);

        damageAndMonitor(event);

        assertFalse(event.isCancelled());
        verify(arena).duelHit(attacker.getUniqueId(),playerId,3);
        verify(arena,never()).eliminate(any(),anyString());
    }

    @Test void lethalDuelDamageCreditsRemainingHealthOnceBeforeEliminationAndSkipsMonitor() {
        Player attacker = duelOpponent();
        doAnswer(call -> { when(manager.getPlayerArena(playerId)).thenReturn(null); return null; })
                .when(arena).eliminate(eq(player),anyString());
        EntityDamageByEntityEvent lethal = hit(attacker,player,100);

        damageAndMonitor(lethal);
        damageAndMonitor(hit(attacker,player,100));

        assertTrue(lethal.isCancelled());
        var order = inOrder(arena);
        order.verify(arena).duelHit(attacker.getUniqueId(),playerId,4);
        order.verify(arena).eliminate(player,"was eliminated");
        verify(arena,times(1)).duelHit(any(),any(),anyDouble());
        verify(arena,times(1)).eliminate(any(),anyString());
    }

    @Test void duelFriendlyFireAndSelfDamageDoNotAwardStatsOrEliminate() {
        when(arena.isDuel()).thenReturn(true);
        for (Player teammate : List.of(anotherParticipant(arena),player)) {
            EntityDamageByEntityEvent event = hit(teammate,player,100);
            damageAndMonitor(event);
            assertTrue(event.isCancelled());
        }
        verify(arena,never()).duelHit(any(),any(),anyDouble());
        verify(arena,never()).eliminate(any(),anyString());
    }

    @Test void activeMembershipAndBothLocationsAreRequiredForDuelDamage() {
        Player attacker = duelOpponent();
        when(arena.isParticipant(attacker.getUniqueId())).thenReturn(false);
        EntityDamageByEntityEvent preparing = hit(attacker,player,100);
        damageAndMonitor(preparing);
        assertTrue(preparing.isCancelled());

        when(arena.isParticipant(attacker.getUniqueId())).thenReturn(true);
        Location outside = new Location(mock(World.class),500,70,500);
        when(arena.contains(outside)).thenReturn(false);
        when(attacker.getLocation()).thenReturn(outside);
        EntityDamageByEntityEvent outsideAttacker = hit(attacker,player,100);
        damageAndMonitor(outsideAttacker);
        assertTrue(outsideAttacker.isCancelled());

        when(attacker.getLocation()).thenReturn(null);
        when(player.getLocation()).thenReturn(outside);
        EntityDamageByEntityEvent outsideVictim = hit(attacker,player,100);
        damageAndMonitor(outsideVictim);
        assertTrue(outsideVictim.isCancelled());
        verify(arena,never()).duelHit(any(),any(),anyDouble());
        verify(arena,never()).eliminate(any(),anyString());
    }

    @Test void duelCountdownCannotApplyDamage() {
        Player attacker = duelOpponent();
        when(arena.getArenaState()).thenReturn(GameState.COUNTDOWN);
        EntityDamageByEntityEvent event = hit(attacker,player,100);
        damageAndMonitor(event);
        assertTrue(event.isCancelled());
        verify(arena,never()).duelHit(any(),any(),anyDouble());
        verify(arena,never()).eliminate(any(),anyString());
    }

    @Test void worldGuardCancelledDamageStaysCancelledAndCombatHandlersRespectCancellation() throws Exception {
        Player attacker = duelOpponent();
        EntityDamageByEntityEvent event = hit(attacker,player,100);
        event.setCancelled(true);
        damageAndMonitor(event);
        assertTrue(event.isCancelled());
        verify(event,never()).setCancelled(false);
        verify(arena,never()).duelHit(any(),any(),anyDouble());
        verify(arena,never()).eliminate(any(),anyString());
        EventHandler damage = ArenaListener.class.getMethod("damage",EntityDamageEvent.class).getAnnotation(EventHandler.class);
        EventHandler monitor = ArenaListener.class.getMethod("damageStatistic",EntityDamageByEntityEvent.class).getAnnotation(EventHandler.class);
        assertTrue(damage.ignoreCancelled());
        assertTrue(monitor.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST,damage.priority());
        assertEquals(EventPriority.MONITOR,monitor.priority());
    }

    @Test void simultaneousDuelsAndMobArenasCannotDamageOneAnother() {
        when(arena.isDuel()).thenReturn(true);
        for (boolean foreignDuel : List.of(false,true)) {
            Arena foreign = mock(Arena.class);
            when(foreign.isDuel()).thenReturn(foreignDuel);
            Player foreignPlayer = anotherParticipant(foreign);
            for (Entity attacker : List.of(foreignPlayer,arrow(foreignPlayer,foreign))) {
                EntityDamageByEntityEvent incoming = hit(attacker,player,100);
                damageAndMonitor(incoming);
                assertTrue(incoming.isCancelled());
            }
            EntityDamageByEntityEvent outgoing = hit(player,foreignPlayer,100);
            damageAndMonitor(outgoing);
            assertTrue(outgoing.isCancelled());
            verify(foreign,never()).duelHit(any(),any(),anyDouble());
            verify(foreign,never()).eliminate(any(),anyString());
        }
        verify(arena,never()).duelHit(any(),any(),anyDouble());
        verify(arena,never()).eliminate(any(),anyString());
    }

    @Test void duelArrowsNeedTheirCurrentArenaTagAndAnActiveShooter() {
        Player attacker = duelOpponent();
        EntityDamageByEntityEvent valid = hit(arrow(attacker,arena),player,3);
        damageAndMonitor(valid);
        assertFalse(valid.isCancelled());
        verify(arena).duelHit(attacker.getUniqueId(),playerId,3);
        clearInvocations(arena);

        for (Arrow invalid : List.of(arrow(attacker,null),arrow(attacker,mock(Arena.class)))) {
            EntityDamageByEntityEvent event = hit(invalid,player,100);
            damageAndMonitor(event);
            assertTrue(event.isCancelled());
        }
        when(manager.getPlayerArena(attacker.getUniqueId())).thenReturn(null);
        EntityDamageByEntityEvent departed = hit(arrow(attacker,arena),player,100);
        damageAndMonitor(departed);
        assertTrue(departed.isCancelled());
        verify(arena,never()).duelHit(any(),any(),anyDouble());
        verify(arena,never()).eliminate(any(),anyString());
    }

    @Test void arrowsAreTaggedOnlyAfterThePlayerHasJoinedTheActiveMatch() {
        Arrow arrow = arrow(player,null);
        ProjectileLaunchEvent event = mock(ProjectileLaunchEvent.class);
        when(event.getEntity()).thenReturn(arrow);
        listener.projectile(event);
        verify(arena).tag(arrow);
        verify(arrow).setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        verify(event,never()).setCancelled(true);

        for (GameState phase : List.of(GameState.WAITING,GameState.COUNTDOWN,GameState.FINISHED)) {
            when(arena.getArenaState()).thenReturn(phase);
            ProjectileLaunchEvent blocked = mock(ProjectileLaunchEvent.class);
            Arrow shot = arrow(player,null);
            when(blocked.getEntity()).thenReturn(shot);
            listener.projectile(blocked);
            verify(blocked).setCancelled(true);
            verify(arena,never()).tag(shot);
        }
    }

    @Test void preparingAndOutsidePlayersCannotLaunchArenaProjectiles() {
        when(arena.isParticipant(playerId)).thenReturn(false);
        ProjectileLaunchEvent preparing = mock(ProjectileLaunchEvent.class);
        Arrow preparingShot = arrow(player,null);
        when(preparing.getEntity()).thenReturn(preparingShot);
        listener.projectile(preparing);
        verify(preparing).setCancelled(true);
        when(arena.isParticipant(playerId)).thenReturn(true);
        when(arena.contains(any())).thenReturn(false);
        ProjectileLaunchEvent outside = mock(ProjectileLaunchEvent.class);
        Arrow outsideShot = arrow(player,null);
        when(outside.getEntity()).thenReturn(outsideShot);
        listener.projectile(outside);
        verify(outside).setCancelled(true);
        verify(arena,never()).tag(any());
    }

    @Test void beneficialDuelSplashesReachOnlySelfAndActiveTeammates() {
        Player enemy = duelOpponent();
        Player ally = anotherParticipant(arena);
        Player eliminatedAlly = anotherParticipant(arena);
        when(arena.isParticipant(eliminatedAlly.getUniqueId())).thenReturn(false);
        PotionSplashEvent event = splash(player,arena,false,player,ally,enemy,eliminatedAlly);
        listener.splash(event);
        verify(event,never()).setIntensity(eq(player),anyDouble());
        verify(event,never()).setIntensity(eq(ally),anyDouble());
        verify(event).setIntensity(enemy,0);
        verify(event).setIntensity(eliminatedAlly,0);
    }

    @Test void harmfulAndMixedDuelSplashesReachOnlyOpponents() {
        Player enemy = duelOpponent();
        Player ally = anotherParticipant(arena);
        for (boolean mixed : List.of(false,true)) {
            PotionSplashEvent event = splash(player,arena,true,player,ally,enemy);
            if (mixed) {
                PotionEffect harmful = event.getPotion().getEffects().iterator().next();
                PotionEffectType helpfulType = mock(PotionEffectType.class);
                when(helpfulType.getEffectCategory()).thenReturn(PotionEffectType.Category.BENEFICIAL);
                PotionEffect helpful = mock(PotionEffect.class);
                when(helpful.getType()).thenReturn(helpfulType);
                when(event.getPotion().getEffects()).thenReturn(List.of(helpful,harmful));
            }
            listener.splash(event);
            verify(event).setIntensity(player,0);
            verify(event).setIntensity(ally,0);
            verify(event,never()).setIntensity(eq(enemy),anyDouble());
        }
    }

    @Test void potionsCannotAffectOutsidersOrPlayersInAnotherConcurrentMatch() {
        when(arena.isDuel()).thenReturn(true);
        Player outsider = mock(Player.class);
        when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
        for (boolean foreignDuel : List.of(false,true)) {
            Arena foreign = mock(Arena.class);
            when(foreign.isDuel()).thenReturn(foreignDuel);
            Player foreignPlayer = anotherParticipant(foreign);
            for (boolean harmful : List.of(false,true)) {
                PotionSplashEvent outgoing = splash(player,arena,harmful,outsider,foreignPlayer);
                listener.splash(outgoing);
                verify(outgoing).setIntensity(outsider,0);
                verify(outgoing).setIntensity(foreignPlayer,0);
                PotionSplashEvent incoming = splash(foreignPlayer,foreign,harmful,player);
                listener.splash(incoming);
                verify(incoming).setIntensity(player,0);
            }
        }
    }

    @Test void outsiderUntaggedAndDepartedShooterPotionsCannotAffectArenaPlayers() {
        Player outsider = mock(Player.class);
        when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
        for (Player shooter : List.of(outsider,player)) {
            PotionSplashEvent untagged = splash(shooter,null,false,player);
            listener.splash(untagged);
            verify(untagged).setIntensity(player,0);
        }
        Player departed = anotherParticipant(arena);
        when(manager.getPlayerArena(departed.getUniqueId())).thenReturn(null);
        PotionSplashEvent old = splash(departed,arena,false,player);
        listener.splash(old);
        verify(old).setIntensity(player,0);
        Player movedToOtherMatch = anotherParticipant(mock(Arena.class));
        PotionSplashEvent previousArena = splash(movedToOtherMatch,arena,false,player);
        listener.splash(previousArena);
        verify(previousArena).setIntensity(player,0);
    }

    @Test void potionThrownBeforeJoiningCannotApplyEffectsToAnOutsidePlayerAfterJoining() {
        Player outsider = mock(Player.class);
        when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
        for (boolean harmful : List.of(false,true)) {
            PotionSplashEvent untagged = splash(player,null,harmful,outsider);
            listener.splash(untagged);
            verify(untagged).setIntensity(outsider,0);
        }
    }

    @Test void taggedPotionsWithMissingOrUntrackedShootersCannotAffectPlayers() {
        PotionSplashEvent missingShooter = splash(null,arena,true,player);
        listener.splash(missingShooter);
        verify(missingShooter).setIntensity(player,0);

        Witch untracked = mock(Witch.class);
        when(untracked.getUniqueId()).thenReturn(UUID.randomUUID());
        when(manager.owner(untracked)).thenReturn(arena);
        PotionSplashEvent staleMob = splash(untracked,arena,true,player);
        listener.splash(staleMob);
        verify(staleMob).setIntensity(player,0);
    }

    @Test void currentTrackedWitchPotionsKeepMobCombatButCannotAffectPreparingPlayers() {
        Witch witch = mock(Witch.class);
        UUID witchId = UUID.randomUUID();
        when(witch.getUniqueId()).thenReturn(witchId);
        when(manager.owner(witch)).thenReturn(arena);
        when(arena.getActiveMobs()).thenReturn(Set.of(witchId));
        Player preparing = anotherParticipant(arena);
        when(arena.isParticipant(preparing.getUniqueId())).thenReturn(false);
        PotionSplashEvent event = splash(witch,arena,true,player,preparing);
        listener.splash(event);
        verify(event,never()).setIntensity(eq(player),anyDouble());
        verify(event).setIntensity(preparing,0);
    }

    @Test void splashTargetsAndShootersMustRemainInsideTheirArena() {
        Player ally = anotherParticipant(arena);
        Location outside = new Location(mock(World.class),500,70,500);
        when(arena.contains(outside)).thenReturn(false);
        when(ally.getLocation()).thenReturn(outside);
        PotionSplashEvent targetOutside = splash(player,arena,false,ally);
        listener.splash(targetOutside);
        verify(targetOutside).setIntensity(ally,0);
        when(ally.getLocation()).thenReturn(null);
        when(player.getLocation()).thenReturn(outside);
        PotionSplashEvent shooterOutside = splash(player,arena,false,ally);
        listener.splash(shooterOutside);
        verify(shooterOutside).setIntensity(ally,0);
    }

    @Test void mobArenaAlliesCanHealBetweenWavesWhileHarmfulSplashesCannotHurtThem() {
        Player ally = anotherParticipant(arena);
        when(arena.getArenaState()).thenReturn(GameState.INTERMISSION);
        PotionSplashEvent healing = splash(player,arena,false,player,ally);
        listener.splash(healing);
        verify(healing,never()).setIntensity(any(),anyDouble());
        ProjectileLaunchEvent launch = mock(ProjectileLaunchEvent.class);
        ThrownPotion potion = healing.getPotion();
        when(launch.getEntity()).thenReturn(potion);
        listener.projectile(launch);
        verify(launch,never()).setCancelled(true);
        verify(arena).tag(healing.getPotion());

        PotionSplashEvent harmful = splash(player,arena,true,player,ally);
        listener.splash(harmful);
        verify(harmful).setIntensity(player,0);
        verify(harmful).setIntensity(ally,0);
    }

    @Test void mobArenaPotionsCanStillAffectItsTrackedMobs() {
        Zombie mob = mock(Zombie.class);
        when(manager.owner(mob)).thenReturn(arena);
        for (boolean harmful : List.of(false,true)) {
            PotionSplashEvent event = splash(player,arena,harmful,mob);
            listener.splash(event);
            verify(event,never()).setIntensity(any(),anyDouble());
        }
    }

    @Test void playerSplashesCannotApplyDuringCountdownOrDuelIntermission() {
        for (GameState phase : List.of(GameState.WAITING,GameState.COUNTDOWN,GameState.INTERMISSION)) {
            when(arena.isDuel()).thenReturn(true);
            when(arena.getArenaState()).thenReturn(phase);
            PotionSplashEvent splash = splash(player,arena,false,player);
            listener.splash(splash);
            verify(splash).setIntensity(player,0);
            ProjectileLaunchEvent launch = mock(ProjectileLaunchEvent.class);
            ThrownPotion potion = splash.getPotion();
            when(launch.getEntity()).thenReturn(potion);
            listener.projectile(launch);
            verify(launch).setCancelled(true);
        }
    }

    @Test void drinkableAndSplashPotionsRemainUsableDuringArenaPlay() {
        for (Material material : List.of(Material.POTION,Material.SPLASH_POTION)) {
            // Paper's Material.isBlock resolves the registry; these are items, never blocks.
            doReturn(null).when(Registry.BLOCK).get(material.getKey());
            PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.getMaterial()).thenReturn(material);
            listener.interact(event);
            verify(event,never()).setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
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
                Skeleton.class, Stray.class, WitherSkeleton.class, Phantom.class)) {
            LivingEntity mob = mock(type);
            when(manager.owner(mob)).thenReturn(arena);
            EntityCombustEvent event = new EntityCombustEvent(mob, 8.0F);

            listener.sunlight(event);

            assertTrue(event.isCancelled(), type.getSimpleName() + " sunlight must be blocked");
            verify(mob).setFireTicks(0);
        }
    }

    @Test void unownedUndeadKeepNormalSunlightBehavior() {
        for (Class<? extends LivingEntity> type : List.of(Zombie.class, Drowned.class, Skeleton.class, Phantom.class)) {
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
        for (Class<? extends LivingEntity> type : List.of(Zombie.class, Drowned.class, Skeleton.class, Phantom.class)) {
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
            PlayerCommandPreprocessEvent classes = new PlayerCommandPreprocessEvent(player,"/ma classes",Set.of());
            listener.commands(classes);
            assertFalse(classes.isCancelled());
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
        verify(arena).mobExploded(creeper);
        verify(arena,never()).mobDeath(any(),any());
    }

    @Test void worldGuardCancelledCreeperExplosionStillSchedulesCompletionWithoutUncancelling() throws Exception {
        Creeper creeper = mock(Creeper.class);
        when(manager.owner(creeper)).thenReturn(arena);
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        when(event.getEntity()).thenReturn(creeper);
        when(event.isCancelled()).thenReturn(true);

        listener.exploded(event);

        verify(arena).mobExploded(creeper);
        verify(event,never()).setCancelled(false);
        assertFalse(ArenaListener.class.getMethod("exploded",EntityExplodeEvent.class)
                .getAnnotation(EventHandler.class).ignoreCancelled());
    }

    @Test void acceptedCreeperPrimeCompletesEvenWhenMobGriefingSuppressesTheBlockExplosionEvent() {
        Creeper creeper = mock(Creeper.class);
        when(manager.owner(creeper)).thenReturn(arena);
        ExplosionPrimeEvent event = new ExplosionPrimeEvent(creeper,3,true);
        listener.prime(event);
        listener.primed(event);
        assertFalse(event.getFire());
        verify(arena).mobExploded(creeper);

        event.setCancelled(true);
        listener.primed(event);
        verify(arena,times(1)).mobExploded(creeper);
        assertTrue(event.isCancelled());
    }

    @Test void ownedCreepersAndWindChargesCannotDestroyOrTriggerArenaBlocks() {
        for (Entity explosive : List.of(mock(Creeper.class),mock(BreezeWindCharge.class))) {
            when(manager.owner(explosive)).thenReturn(arena);
            java.util.ArrayList<org.bukkit.block.Block> blocks = new java.util.ArrayList<>(List.of(mock(org.bukkit.block.Block.class)));
            EntityExplodeEvent event = mock(EntityExplodeEvent.class);
            when(event.getEntity()).thenReturn(explosive);
            when(event.blockList()).thenReturn(blocks);
            listener.explode(event);
            assertTrue(blocks.isEmpty());
            verify(event).setYield(0);
            verify(event,never()).setCancelled(false);
        }
    }

    @Test void endermenCannotPickUpOrPlaceBlocksAndOwnedMobsCannotTriggerPressurePlates() {
        Enderman enderman = mock(Enderman.class);
        when(manager.owner(enderman)).thenReturn(arena);
        EntityChangeBlockEvent change = mock(EntityChangeBlockEvent.class);
        when(change.getEntity()).thenReturn(enderman);
        listener.change(change);
        verify(change).setCancelled(true);
        EntityInteractEvent interact = mock(EntityInteractEvent.class);
        when(interact.getEntity()).thenReturn(enderman);
        listener.mobInteract(interact);
        verify(interact).setCancelled(true);
    }

    @Test void activeEvokerFangsAreTaggedAndDamageOnlyTheirOwnPlayers() {
        Evoker evoker = mock(Evoker.class);
        UUID id = UUID.randomUUID();
        when(evoker.getUniqueId()).thenReturn(id);
        when(manager.owner(evoker)).thenReturn(arena);
        doReturn(true).when(arena).isCombatMob(id);
        EvokerFangs fangs = mock(EvokerFangs.class);
        when(fangs.getOwner()).thenReturn(evoker);
        EntitySpawnEvent spawn = new EntitySpawnEvent(fangs);
        listener.spellSpawn(spawn);
        assertFalse(spawn.isCancelled());
        verify(arena).tag(fangs);
        EntityDamageByEntityEvent ownPlayer = hit(fangs,player,1);
        listener.damage(ownPlayer);
        assertFalse(ownPlayer.isCancelled());
        Player outsider = mock(Player.class);
        when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityDamageByEntityEvent outsidePlayer = hit(fangs,outsider,1);
        listener.damage(outsidePlayer);
        assertTrue(outsidePlayer.isCancelled());
        doReturn(false).when(arena).isCombatMob(id);
        EntityDamageByEntityEvent expiredSource = hit(fangs,player,1);
        listener.damage(expiredSource);
        assertTrue(expiredSource.isCancelled());
    }

    @Test void evokerFangsOutsideTheirArenaAndCreeperPotionCloudsAreRejected() {
        Evoker evoker = mock(Evoker.class);
        when(manager.owner(evoker)).thenReturn(arena);
        doReturn(true).when(arena).isCombatMob(any());
        when(arena.contains(any())).thenReturn(false);
        EvokerFangs fangs = mock(EvokerFangs.class);
        when(fangs.getOwner()).thenReturn(evoker);
        EntitySpawnEvent spawn = new EntitySpawnEvent(fangs);
        listener.spellSpawn(spawn);
        assertTrue(spawn.isCancelled());
        verify(arena,never()).tag(fangs);

        AreaEffectCloud cloud = mock(AreaEffectCloud.class);
        Location location = new Location(mock(World.class),5,65,5);
        when(cloud.getLocation()).thenReturn(location);
        when(manager.at(location)).thenReturn(arena);
        when(manager.occupied(arena)).thenReturn(true);
        EntitySpawnEvent cloudSpawn = new EntitySpawnEvent(cloud);
        listener.spellSpawn(cloudSpawn);
        assertTrue(cloudSpawn.isCancelled());
    }

    @Test void summonedVexNeedsCurrentSummonerAndCapacityBeforeItCanSpawn() {
        Location location = new Location(mock(World.class),5,65,5);
        when(manager.at(location)).thenReturn(arena);
        Vex vex = spawnMob(Vex.class,location,null);
        Evoker evoker = mock(Evoker.class);
        when(vex.getOwner()).thenReturn(evoker);
        when(manager.owner(evoker)).thenReturn(arena);
        when(arena.adoptSummonedMob(vex,evoker)).thenReturn(true);
        when(arena.owns(vex)).thenReturn(true);
        CreatureSpawnEvent accepted = new CreatureSpawnEvent(vex,CreatureSpawnEvent.SpawnReason.SPELL);
        listener.spawn(accepted);
        assertFalse(accepted.isCancelled());
        verify(arena).adoptSummonedMob(vex,evoker);

        when(arena.adoptSummonedMob(vex,evoker)).thenReturn(false);
        CreatureSpawnEvent exhausted = new CreatureSpawnEvent(vex,CreatureSpawnEvent.SpawnReason.SPELL);
        listener.spawn(exhausted);
        assertTrue(exhausted.isCancelled());
        when(manager.at(location)).thenReturn(null);
        CreatureSpawnEvent escaped = new CreatureSpawnEvent(vex,CreatureSpawnEvent.SpawnReason.SPELL);
        listener.spawn(escaped);
        assertTrue(escaped.isCancelled());
        verify(arena,times(2)).adoptSummonedMob(vex,evoker);
    }

    @Test void canceledVexSpawnDoesNotRegisterABonusMob() {
        Vex vex = mock(Vex.class);
        Evoker evoker = mock(Evoker.class);
        when(vex.getOwner()).thenReturn(evoker);
        when(manager.owner(evoker)).thenReturn(arena);
        CreatureSpawnEvent spawn = new CreatureSpawnEvent(vex,CreatureSpawnEvent.SpawnReason.SPELL);
        spawn.setCancelled(true);
        listener.spawn(spawn);
        assertTrue(spawn.isCancelled());
        verify(arena,never()).adoptSummonedMob(any(),any());
    }

    @Test void windBurstsCannotPushOutsidersOrPlayersFromAnotherMatch() {
        Breeze breeze = mock(Breeze.class);
        UUID id = UUID.randomUUID();
        when(breeze.getUniqueId()).thenReturn(id);
        doReturn(true).when(arena).isCombatMob(id);
        when(manager.owner(breeze)).thenReturn(arena);
        BreezeWindCharge charge = mock(BreezeWindCharge.class);
        when(charge.getShooter()).thenReturn(breeze);
        when(manager.owner(charge)).thenReturn(arena);
        Player outsider = mock(Player.class);
        when(outsider.getUniqueId()).thenReturn(UUID.randomUUID());
        Player elsewhere = anotherParticipant(mock(Arena.class));
        for (Player target : List.of(player,outsider,elsewhere)) {
            var push = mock(io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent.class);
            when(push.getPushedBy()).thenReturn(charge);
            when(push.getEntity()).thenReturn(target);
            listener.pushed(push);
            if (target == player) verify(push,never()).setCancelled(true);
            else verify(push).setCancelled(true);
        }
    }

    @Test void escapedOwnedProjectilesAreRemovedBeforeTheirImpactCanRun() {
        BreezeWindCharge charge = mock(BreezeWindCharge.class);
        when(manager.owner(charge)).thenReturn(arena);
        when(arena.contains(any())).thenReturn(false);
        ProjectileHitEvent hit = mock(ProjectileHitEvent.class);
        when(hit.getEntity()).thenReturn(charge);
        listener.projectileHit(hit);
        verify(hit).setCancelled(true);
        verify(charge).remove();
    }

    @Test void successfulArenaBeeStingResetsAfterTheNativeAttackHasFinished() {
        Bee bee = mock(Bee.class);
        UUID id = UUID.randomUUID();
        when(bee.getUniqueId()).thenReturn(id);
        when(bee.isValid()).thenReturn(true);
        when(manager.owner(bee)).thenReturn(arena);
        doReturn(true).when(arena).isCombatMob(id);
        org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        org.mockito.ArgumentCaptor<Runnable> task = org.mockito.ArgumentCaptor.forClass(Runnable.class);

        listener.beeSting(hit(bee,player,1));

        verify(scheduler).runTask(eq(plugin),task.capture());
        verify(bee,never()).setHasStung(anyBoolean());
        task.getValue().run();
        verify(bee).setHasStung(false);
        verify(bee).setTimeSinceSting(0);
        verify(bee).setAnger(1200);
    }

    @Test void beeStingRecoveryDoesNotModifyExpiredMobsOrAnotherSession() {
        org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        for (int expired = 0; expired < 4; expired++) {
            clearInvocations(scheduler);
            Bee bee = mock(Bee.class);
            UUID id = UUID.randomUUID();
            when(bee.getUniqueId()).thenReturn(id);
            when(bee.isValid()).thenReturn(true);
            when(manager.owner(bee)).thenReturn(arena);
            doReturn(true).when(arena).isCombatMob(id);
            listener.beeSting(hit(bee,player,1));
            org.mockito.ArgumentCaptor<Runnable> task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTask(eq(plugin),task.capture());
            switch (expired) {
                case 0 -> when(bee.isValid()).thenReturn(false);
                case 1 -> when(manager.owner(bee)).thenReturn(null);
                case 2 -> doReturn(false).when(arena).isCombatMob(id);
                case 3 -> when(arena.getSessionId()).thenReturn(UUID.randomUUID());
                default -> throw new AssertionError();
            }
            task.getValue().run();
            verify(bee,never()).setHasStung(anyBoolean());
            verify(bee,never()).setTimeSinceSting(anyInt());
            verify(bee,never()).setAnger(anyInt());
        }
    }

    @Test void unownedAndCancelledBeeHitsNeverScheduleRecoveryButAbsorbedHitsDo() {
        org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        Bee bee = mock(Bee.class);
        UUID id = UUID.randomUUID();
        when(bee.getUniqueId()).thenReturn(id);
        listener.beeSting(hit(bee,player,1));
        when(manager.owner(bee)).thenReturn(arena);
        doReturn(true).when(arena).isCombatMob(id);
        EntityDamageByEntityEvent cancelled = hit(bee,player,1);
        cancelled.setCancelled(true);
        listener.beeSting(cancelled);
        verifyNoInteractions(scheduler);
        listener.beeSting(hit(bee,player,0));
        verify(scheduler).runTask(eq(plugin),any(Runnable.class));
    }

    @Test void staffBypassAllowsBuildingAndEntryOnlyWhileOutsideAMatch() {
        when(manager.getPlayerArena(playerId)).thenReturn(null);
        when(manager.canBypass(player)).thenReturn(true);
        Location location = new Location(mock(World.class),5,65,5);
        when(manager.at(location)).thenReturn(arena);
        when(manager.occupied(arena)).thenReturn(true);
        org.bukkit.block.Block block = mock(org.bukkit.block.Block.class);
        when(block.getLocation()).thenReturn(location);
        org.bukkit.event.block.BlockBreakEvent breaking = new org.bukkit.event.block.BlockBreakEvent(block,player);
        listener.breakBlock(breaking);
        assertFalse(breaking.isCancelled());
        PlayerMoveEvent move = new PlayerMoveEvent(player,location,location);
        listener.move(move);
        assertFalse(move.isCancelled());
        PlayerTeleportEvent teleport = new PlayerTeleportEvent(player,location,location);
        listener.teleport(teleport);
        assertFalse(teleport.isCancelled());
        verify(manager,never()).eject(any(),any());

        breaking.setCancelled(true);
        listener.breakBlock(breaking);
        assertTrue(breaking.isCancelled(),"WorldGuard cancellation must be preserved");
        when(manager.getPlayerArena(playerId)).thenReturn(arena);
        org.bukkit.event.block.BlockBreakEvent participant = new org.bukkit.event.block.BlockBreakEvent(block,player);
        listener.breakBlock(participant);
        assertTrue(participant.isCancelled());
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

    @SuppressWarnings({ "deprecation", "removal" })
    private static final class TestEffectType extends PotionEffectType {
        private final Key key;
        TestEffectType(Key key) { this.key = key; }
        @Override public NamespacedKey getKey() { return new NamespacedKey(key.namespace(),key.value()); }
        @Override public String getTranslationKey() { return "effect.minecraft." + key.value(); }
        @Override public String translationKey() { return getTranslationKey(); }
        @Override public PotionEffect createEffect(int duration,int amplifier) { return new PotionEffect(this,duration,amplifier); }
        @Override public boolean isInstant() { return key.value().startsWith("instant_"); }
        @Override public org.bukkit.potion.PotionEffectTypeCategory getCategory() { return null; }
        @Override public Color getColor() { return Color.BLUE; }
        @Override public double getDurationModifier() { return 1; }
        @Override public int getId() { return 1; }
        @Override public String getName() { return key.value(); }
        @Override public Map<Attribute,AttributeModifier> getEffectAttributes() { return Map.of(); }
        @Override public double getAttributeModifierAmount(Attribute attribute,int amplifier) { return 0; }
        @Override public Category getEffectCategory() { return Category.BENEFICIAL; }
    }
}
