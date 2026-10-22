package cloud.mineplay.mobarena.mobs;

import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.entity.memory.MemoryKey;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Arena-only setup and target maintenance. Called on the server thread for owned mobs. */
public final class MobBehavior {
    private static final int ANGER_TICKS = 1200;
    private static final int GOAT_RAM_INTERVAL = 100;

    private MobBehavior() {}

    /** Called after equipment is assigned, once for each newly spawned wave mob. */
    public static boolean configureVariant(Mob mob, ConfigurationSection settings, int wave,
                                           Predicate<Player> validTarget) {
        if (mob.getType() != EntityType.SKELETON || !(mob instanceof Skeleton skeleton)) return false;
        RapidFireSettings rapidFire = RapidFireSettings.read(settings);
        if (rapidFire == null || wave < rapidFire.minimumWave() || !RapidSkeletonGoal.holdsBow(skeleton)
                || !MobSpawn.roll(rapidFire.chance())) return false;
        MobGoals goals = skeleton.getServer().getMobGoals();
        goals.removeGoal(skeleton, VanillaGoal.RANGED_BOW_ATTACK);
        goals.addGoal(skeleton, 4, new RapidSkeletonGoal(skeleton, rapidFire.intervalTicks(), validTarget));
        skeleton.customName(Component.text("Machine Gun Skeleton", NamedTextColor.GOLD));
        skeleton.setCustomNameVisible(true);
        return true;
    }

    public static void configure(Mob mob) {
        if (mob instanceof Raider raider) {
            raider.setCanJoinRaid(false);
            raider.setPatrolLeader(false);
            raider.setPatrolTarget(null);
        }
        if (mob instanceof PiglinAbstract piglin) {
            piglin.setImmuneToZombification(true);
            piglin.setBaby(false);
        }
        if (mob instanceof Piglin piglin) {
            piglin.setIsAbleToHunt(false);
            disableAdmiration(piglin);
        }
        if (mob instanceof IronGolem golem) golem.setPlayerCreated(false);
        if (mob instanceof PigZombie zombie) zombie.setAnger(ANGER_TICKS);
        if (mob instanceof Goat goat) {
            goat.setAdult();
            goat.setAgeLock(true);
            goat.setBreed(false);
        }
        if (mob instanceof Vex vex) {
            // Spawned wave mobs must remain until defeated; summons are cleaned up with their evoker.
            vex.setLimitedLifetime(false);
            vex.setBound(mob.getLocation());
        }
        if (mob instanceof Phantom phantom) phantom.setAnchorLocation(mob.getLocation());
        if (mob instanceof Bee bee) {
            bee.setAdult();
            bee.setAgeLock(true);
            bee.setBreed(false);
            bee.setHive(null);
            bee.setFlower(null);
            bee.setHasNectar(false);
            keepBeeAngry(bee);
            MobGoals goals = mob.getServer().getMobGoals();
            goals.removeGoal(bee, VanillaGoal.BEE_GROW_CROP);
            goals.removeGoal(bee, VanillaGoal.BEE_POLLINATE);
            goals.removeGoal(bee, VanillaGoal.BEE_ENTER_HIVE);
            goals.removeGoal(bee, VanillaGoal.BEE_GO_TO_HIVE);
            goals.removeGoal(bee, VanillaGoal.BEE_GO_TO_KNOWN_FLOWER);
        }
        if (mob instanceof Enderman enderman) {
            enderman.setCarriedBlock(null);
            MobGoals goals = mob.getServer().getMobGoals();
            goals.removeGoal(enderman, VanillaGoal.ENDERMAN_TAKE_BLOCK);
            goals.removeGoal(enderman, VanillaGoal.ENDERMAN_LEAVE_BLOCK);
        }
        if (mob instanceof Evoker evoker) {
            mob.getServer().getMobGoals().removeGoal(evoker, VanillaGoal.EVOKER_WOLOLO_SPELL);
        }
        if (mob instanceof Llama llama) {
            llama.setAdult();
            llama.setAgeLock(true);
            llama.setBreed(false);
            llama.setTamed(false);
            llama.leaveCaravan();
            MobGoals goals = mob.getServer().getMobGoals();
            goals.removeGoal(llama, VanillaGoal.LLAMA_FOLLOW_CARAVAN);
            goals.removeGoal(llama, VanillaGoal.LLAMA_ATTACK_WOLF);
            // This retaliation goal clears the target after one spit. Keep the native ranged goal.
            goals.removeGoal(llama, VanillaGoal.LLAMA_HURT_BY);
        }
    }

    /** The caller selects a live participant from this arena and supplies an anchor inside its bounds. */
    public static void retarget(Mob mob, Player target, Location anchor, long tick) {
        if (mob instanceof PiglinAbstract piglin) {
            // Piglin combat uses brain memories, including when its opponent wears gold armour.
            piglin.setMemory(MemoryKey.ANGRY_AT, target == null ? null : target.getUniqueId());
            piglin.setMemory(MemoryKey.UNIVERSAL_ANGER, target == null ? null : true);
            if (piglin instanceof Piglin ordinary) disableAdmiration(ordinary);
        }
        if (mob instanceof Bee bee) keepBeeAngry(bee);
        if (mob instanceof PigZombie zombie) zombie.setAnger(ANGER_TICKS);
        mob.setTarget(target);
        mob.setAggressive(target != null);
        if (mob instanceof Enderman enderman) enderman.setScreaming(target != null);
        if (anchor != null) {
            if (mob instanceof Phantom phantom) phantom.setAnchorLocation(anchor);
            if (mob instanceof Vex vex) vex.setBound(anchor);
        }
        if (target == null) return;
        if (mob instanceof Goat goat && tick % GOAT_RAM_INTERVAL == 0 && goat.hasLineOfSight(target)) {
            // Setting Mob.target alone does not activate a goat's brain-driven ram attack.
            goat.setMemory(MemoryKey.IS_PANICKING, null);
            goat.setMemory(MemoryKey.LONG_JUMP_COOLING_DOWN, 200);
            goat.ram(target);
        }
    }

    private static void disableAdmiration(Piglin piglin) {
        piglin.setMemory(MemoryKey.ADMIRING_ITEM, null);
        piglin.setMemory(MemoryKey.ADMIRING_DISABLED, true);
        piglin.setMemory(MemoryKey.DISABLE_WALK_TO_ADMIRE_ITEM, true);
    }

    private static void keepBeeAngry(Bee bee) {
        bee.setAnger(ANGER_TICKS);
        bee.setCannotEnterHiveTicks(ANGER_TICKS);
        // Ordinary bees die after stinging. Arena bees remain a valid wave mob and can attack again.
        bee.setHasStung(false);
        bee.setTimeSinceSting(0);
    }
}
