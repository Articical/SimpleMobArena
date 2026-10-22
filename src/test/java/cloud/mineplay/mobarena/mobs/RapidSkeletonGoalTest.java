package cloud.mineplay.mobarena.mobs;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Stray;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RapidSkeletonGoalTest {
    private Skeleton skeleton;
    private Player player;
    private Pathfinder pathfinder;
    private EntityEquipment equipment;
    private MobGoals goals;
    private World world;
    private final AtomicInteger age = new AtomicInteger();
    private final AtomicBoolean participant = new AtomicBoolean(true);
    private RapidSkeletonGoal goal;

    @BeforeEach void setup() {
        skeleton = mock(Skeleton.class);
        player = mock(Player.class);
        world = mock(World.class);
        when(skeleton.getType()).thenReturn(EntityType.SKELETON);
        when(skeleton.isValid()).thenReturn(true);
        when(skeleton.getWorld()).thenReturn(world);
        when(skeleton.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(skeleton.getTicksLived()).thenAnswer(call -> age.get());
        when(skeleton.getTarget()).thenReturn(player);
        when(skeleton.hasLineOfSight(player)).thenReturn(true);
        pathfinder = mock(Pathfinder.class);
        when(skeleton.getPathfinder()).thenReturn(pathfinder);
        equipment = mock(EntityEquipment.class);
        when(skeleton.getEquipment()).thenReturn(equipment);
        doReturn(item(Material.BOW)).when(equipment).getItemInMainHand();
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 8, 64, 0));
        when(player.isOnline()).thenReturn(true);
        when(player.isValid()).thenReturn(true);
        Server server = mock(Server.class);
        goals = mock(MobGoals.class);
        when(skeleton.getServer()).thenReturn(server);
        when(server.getMobGoals()).thenReturn(goals);
        goal = new RapidSkeletonGoal(skeleton, 8, ignored -> participant.get());
    }

    @Test void actualGameTicksDetermineFireRateWhenPaperOnlyTicksTheGoalEveryOtherTick() {
        assertTrue(goal.shouldActivate());
        goal.start();
        for (int tick = 2; tick <= 32; tick += 2) {
            age.set(tick);
            goal.tick();
            verify(skeleton, times(tick / 8)).rangedAttack(player, 1.0f);
        }
        verify(skeleton).setChargingAttack(true);
        verify(pathfinder, never()).moveTo(player, 1.0);
    }

    @Test void blockedSightPreventsShotsAndRestartsTheWindupInsteadOfBankingABurst() {
        goal.start();
        when(skeleton.hasLineOfSight(player)).thenReturn(false);
        age.set(20);
        goal.tick();
        age.set(22);
        goal.tick();
        verify(skeleton, never()).rangedAttack(any(), anyFloat());
        verify(pathfinder, times(1)).moveTo(player, 1.0);

        when(skeleton.hasLineOfSight(player)).thenReturn(true);
        age.set(28);
        goal.tick();
        verify(skeleton, never()).rangedAttack(any(), anyFloat());
        age.set(30);
        goal.tick();
        verify(skeleton).rangedAttack(player, 1.0f);
    }

    @Test void targetsBeyondSixteenBlocksAreApproachedButNotShot() {
        goal.start();
        when(player.getLocation()).thenReturn(new Location(world, 16.1, 64, 0));
        age.set(8);
        goal.tick();
        verify(skeleton, never()).rangedAttack(any(), anyFloat());
        verify(pathfinder).moveTo(player, 1.0);
        when(player.getLocation()).thenReturn(new Location(world, 16, 64, 0));
        age.set(16);
        goal.tick();
        verify(skeleton).rangedAttack(player, 1.0f);
    }

    @Test void membershipOrArenaContainmentIsCheckedAgainBetweenActivationAndShooting() {
        assertTrue(goal.shouldActivate());
        goal.start();
        participant.set(false);
        age.set(8);
        goal.tick();
        assertFalse(goal.shouldStayActive());
        verify(skeleton, never()).rangedAttack(any(), anyFloat());
        verify(skeleton).setChargingAttack(false);
        verify(pathfinder).stopPathfinding();
    }

    @Test void offlineDeadOrWrongWorldTargetsCannotActivateTheGoal() {
        when(player.isOnline()).thenReturn(false);
        assertFalse(goal.shouldActivate());
        when(player.isOnline()).thenReturn(true);
        when(player.isDead()).thenReturn(true);
        assertFalse(goal.shouldActivate());
        when(player.isDead()).thenReturn(false);
        doReturn(mock(World.class)).when(player).getWorld();
        assertFalse(goal.shouldActivate());
    }

    @Test void aRemovedSkeletonOrMissingBowStopsAttacking() {
        when(skeleton.isValid()).thenReturn(false);
        assertFalse(goal.shouldActivate());
        when(skeleton.isValid()).thenReturn(true);
        doReturn(item(Material.STONE_SWORD)).when(equipment).getItemInMainHand();
        assertFalse(goal.shouldActivate());
        age.set(8);
        goal.tick();
        verify(skeleton, never()).rangedAttack(any(), anyFloat());
    }

    @Test void selectedVariantReplacesTheNativeBowGoalAndDisplaysItsName() {
        assertTrue(MobBehavior.configureVariant(skeleton, settings(1), 8, ignored -> true));
        verify(goals).removeGoal(skeleton, VanillaGoal.RANGED_BOW_ATTACK);
        ArgumentCaptor<Goal<Skeleton>> registered = ArgumentCaptor.captor();
        verify(goals).addGoal(eq(skeleton), eq(4), registered.capture());
        assertInstanceOf(RapidSkeletonGoal.class, registered.getValue());
        verify(skeleton).customName(argThat(name -> name != null && PlainTextComponentSerializer.plainText()
                .serialize(name).equals("Machine Gun Skeleton")));
        verify(skeleton).setCustomNameVisible(true);
    }

    @Test void chanceWaveTypeAndEquipmentGateTheVariantWithoutChangingOrdinarySkeletons() {
        assertFalse(MobBehavior.configureVariant(skeleton, settings(0), 8, ignored -> true));
        assertFalse(MobBehavior.configureVariant(skeleton, settings(1), 7, ignored -> true));
        assertFalse(MobBehavior.configureVariant(skeleton, new YamlConfiguration(), 8, ignored -> true));
        doReturn(item(Material.STONE_SWORD)).when(equipment).getItemInMainHand();
        assertFalse(MobBehavior.configureVariant(skeleton, settings(1), 8, ignored -> true));
        Stray stray = mock(Stray.class);
        when(stray.getType()).thenReturn(EntityType.STRAY);
        assertFalse(MobBehavior.configureVariant(stray, settings(1), 8, ignored -> true));
        verifyNoInteractions(goals);
        verify(skeleton, never()).customName(any());
    }

    private static ItemStack item(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        return item;
    }

    private static YamlConfiguration settings(double chance) {
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("rapid-fire.chance", chance);
        settings.set("rapid-fire.minimum-wave", 8);
        settings.set("rapid-fire.interval-ticks", 8);
        return settings;
    }
}
