package cloud.mineplay.mobarena.mobs;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;

import java.util.EnumSet;
import java.util.function.Predicate;

/** Uses the skeleton's native ranged attack, including normal bow/projectile events. */
final class RapidSkeletonGoal implements Goal<Skeleton> {
    private static final GoalKey<Skeleton> KEY = GoalKey.of(Skeleton.class,
            new NamespacedKey("simplemobarena", "rapid_skeleton"));
    private static final double RANGE_SQUARED = 16 * 16;
    private final Skeleton skeleton;
    private final int intervalTicks;
    private final Predicate<Player> allowedTarget;
    private int nextShotTick;
    private int nextPathTick;

    RapidSkeletonGoal(Skeleton skeleton, int intervalTicks, Predicate<Player> allowedTarget) {
        if (intervalTicks < 4 || intervalTicks > 100) throw new IllegalArgumentException("Invalid shooting interval");
        this.skeleton = java.util.Objects.requireNonNull(skeleton);
        this.intervalTicks = intervalTicks;
        this.allowedTarget = java.util.Objects.requireNonNull(allowedTarget);
    }

    static boolean holdsBow(Skeleton skeleton) {
        return skeleton.getEquipment() != null
                && skeleton.getEquipment().getItemInMainHand().getType() == Material.BOW;
    }

    private Player target() {
        if (!skeleton.isValid() || skeleton.isDead() || !holdsBow(skeleton)
                || !(skeleton.getTarget() instanceof Player player)
                || !player.isOnline() || !player.isValid() || player.isDead()
                || !skeleton.getWorld().equals(player.getWorld()) || !allowedTarget.test(player)) return null;
        return player;
    }

    @Override public boolean shouldActivate() { return target() != null; }
    @Override public boolean shouldStayActive() { return target() != null; }

    @Override public void start() {
        nextShotTick = skeleton.getTicksLived() + intervalTicks;
        nextPathTick = skeleton.getTicksLived();
        skeleton.setChargingAttack(true);
    }

    @Override public void stop() {
        skeleton.setChargingAttack(false);
        skeleton.getPathfinder().stopPathfinding();
    }

    @Override public void tick() {
        // Re-check membership immediately before every attack, even between goal-selector checks.
        Player player = target();
        if (player == null) {
            stop();
            return;
        }
        int now = skeleton.getTicksLived();
        skeleton.lookAt(player, 30, 30);
        boolean canShoot = skeleton.getLocation().distanceSquared(player.getLocation()) <= RANGE_SQUARED
                && skeleton.hasLineOfSight(player);
        if (!canShoot) {
            nextShotTick = now + intervalTicks;
            if (now >= nextPathTick) {
                skeleton.getPathfinder().moveTo(player, 1.0);
                nextPathTick = now + 10;
            }
            return;
        }
        skeleton.getPathfinder().stopPathfinding();
        if (now >= nextShotTick) {
            // Paper can tick custom goals every other game tick. Use entity age, not callback counts.
            nextShotTick = now + intervalTicks;
            skeleton.rangedAttack(player, 1.0f);
        }
    }

    @Override public GoalKey<Skeleton> getKey() { return KEY; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE, GoalType.LOOK); }
}
