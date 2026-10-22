package cloud.mineplay.mobarena.mobs;

import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.entity.memory.MemoryKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

class MobBehaviorTest {
    @Test void piglinsStayAdultAndDoNotConvertOrAdmireGoldInsteadOfFighting() {
        Piglin piglin = mock(Piglin.class);
        MobBehavior.configure(piglin);
        verify(piglin).setImmuneToZombification(true);
        verify(piglin).setBaby(false);
        verify(piglin).setIsAbleToHunt(false);
        verify(piglin).setMemory(MemoryKey.ADMIRING_ITEM, null);
        verify(piglin).setMemory(MemoryKey.ADMIRING_DISABLED, true);
        verify(piglin).setMemory(MemoryKey.DISABLE_WALK_TO_ADMIRE_ITEM, true);

        Player player = player();
        MobBehavior.retarget(piglin, player, null, 20);
        verify(piglin).setMemory(MemoryKey.ANGRY_AT, player.getUniqueId());
        verify(piglin).setMemory(MemoryKey.UNIVERSAL_ANGER, true);
        verify(piglin).setTarget(player);
        MobBehavior.retarget(piglin, null, null, 40);
        verify(piglin).setMemory(MemoryKey.ANGRY_AT, null);
        verify(piglin).setMemory(MemoryKey.UNIVERSAL_ANGER, null);
        verify(piglin).setTarget(null);
    }

    @Test void piglinBrutesAlsoStayAdultAndDoNotConvert() {
        PiglinBrute brute = mock(PiglinBrute.class);
        MobBehavior.configure(brute);
        verify(brute).setImmuneToZombification(true);
        verify(brute).setBaby(false);
    }

    @Test void arenaBeesKeepAttackingAfterAStingWithoutEnteringHivesOrGrowingCrops() {
        Bee bee = mock(Bee.class);
        MobGoals goals = goals(bee);
        MobBehavior.configure(bee);
        verify(bee).setHive(null);
        verify(bee).setFlower(null);
        verify(bee).setBreed(false);
        verify(goals).removeGoal(bee, VanillaGoal.BEE_GROW_CROP);
        verify(goals).removeGoal(bee, VanillaGoal.BEE_POLLINATE);
        verify(goals).removeGoal(bee, VanillaGoal.BEE_ENTER_HIVE);
        verify(goals).removeGoal(bee, VanillaGoal.BEE_GO_TO_HIVE);
        verify(goals).removeGoal(bee, VanillaGoal.BEE_GO_TO_KNOWN_FLOWER);
        Player first = player(), second = player();
        MobBehavior.retarget(bee, first, null, 20);
        MobBehavior.retarget(bee, second, null, 40);
        verify(bee, times(3)).setAnger(1200);
        verify(bee, times(3)).setHasStung(false);
        verify(bee, times(3)).setTimeSinceSting(0);
        verify(bee, times(3)).setCannotEnterHiveTicks(1200);
        verify(bee).setTarget(first);
        verify(bee).setTarget(second);
    }

    @Test void ironGolemsCanAttackPlayersWithoutBeingHitFirst() {
        IronGolem golem = mock(IronGolem.class);
        Player player = player();
        MobBehavior.configure(golem);
        MobBehavior.retarget(golem, player, null, 20);
        verify(golem).setPlayerCreated(false);
        verify(golem).setTarget(player);
        verify(golem).setAggressive(true);
    }

    @Test void goatsActuallyRamOnABoundedCadenceAndRequireLineOfSight() {
        Goat goat = mock(Goat.class);
        Player player = player();
        MobBehavior.configure(goat);
        when(goat.hasLineOfSight(player)).thenReturn(true);
        for (long tick : List.of(20L, 40L, 60L, 80L, 100L, 120L))
            MobBehavior.retarget(goat, player, null, tick);
        verify(goat).ram(player);
        verify(goat).setAdult();
        verify(goat).setAgeLock(true);
        verify(goat).setBreed(false);
        when(goat.hasLineOfSight(player)).thenReturn(false);
        MobBehavior.retarget(goat, player, null, 200);
        MobBehavior.retarget(goat, null, null, 300);
        verify(goat, times(1)).ram(any());
    }

    @Test void llamasKeepTheirRangedAttackAndLoseRetaliationOnlyAndCaravanBehaviour() {
        Llama llama = mock(Llama.class);
        MobGoals goals = goals(llama);
        Player first = player(), second = player();
        MobBehavior.configure(llama);
        MobBehavior.retarget(llama, first, null, 20);
        MobBehavior.retarget(llama, second, null, 40);
        verify(llama).leaveCaravan();
        verify(llama).setTamed(false);
        verify(goals).removeGoal(llama, VanillaGoal.LLAMA_HURT_BY);
        verify(goals).removeGoal(llama, VanillaGoal.LLAMA_FOLLOW_CARAVAN);
        verify(goals).removeGoal(llama, VanillaGoal.LLAMA_ATTACK_WOLF);
        verify(goals, never()).removeGoal(llama, VanillaGoal.RANGED_ATTACK);
        verify(llama).setTarget(first);
        verify(llama).setTarget(second);
    }

    @Test void endermenCannotTakeOrPlaceBlocksAndBecomeAngryAtSelectedPlayers() {
        Enderman enderman = mock(Enderman.class);
        MobGoals goals = goals(enderman);
        MobBehavior.configure(enderman);
        verify(enderman).setCarriedBlock(null);
        verify(goals).removeGoal(enderman, VanillaGoal.ENDERMAN_TAKE_BLOCK);
        verify(goals).removeGoal(enderman, VanillaGoal.ENDERMAN_LEAVE_BLOCK);
        MobBehavior.retarget(enderman, player(), null, 20);
        verify(enderman).setScreaming(true);
        MobBehavior.retarget(enderman, null, null, 40);
        verify(enderman).setScreaming(false);
    }

    @Test void flyingMobAnchorsFollowTheLocationClampedByTheirArena() {
        World world = mock(World.class);
        Location spawn = new Location(world, 3, 64, 3), anchor = new Location(world, 4, 68, 4);
        Vex vex = mock(Vex.class);
        Phantom phantom = mock(Phantom.class);
        when(vex.getLocation()).thenReturn(spawn);
        when(phantom.getLocation()).thenReturn(spawn);
        MobBehavior.configure(vex);
        MobBehavior.configure(phantom);
        verify(vex).setLimitedLifetime(false);
        verify(vex).setBound(spawn);
        verify(phantom).setAnchorLocation(spawn);
        Player player = player();
        MobBehavior.retarget(vex, player, anchor, 20);
        MobBehavior.retarget(phantom, player, anchor, 20);
        verify(vex).setBound(anchor);
        verify(phantom).setAnchorLocation(anchor);
    }

    @Test void raidersCannotJoinWorldRaidsAndEvokersCannotRecolourSheep() {
        Evoker evoker = mock(Evoker.class);
        MobGoals goals = goals(evoker);
        for (Raider raider : List.of(mock(Pillager.class), mock(Vindicator.class), mock(Ravager.class), evoker)) {
            MobBehavior.configure(raider);
            verify(raider).setCanJoinRaid(false);
            verify(raider).setPatrolLeader(false);
            verify(raider).setPatrolTarget(null);
        }
        verify(goals).removeGoal(evoker, VanillaGoal.EVOKER_WOLOLO_SPELL);
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static MobGoals goals(Mob mob) {
        Server server = mock(Server.class);
        MobGoals goals = mock(MobGoals.class);
        when(mob.getServer()).thenReturn(server);
        when(server.getMobGoals()).thenReturn(goals);
        return goals;
    }
}
