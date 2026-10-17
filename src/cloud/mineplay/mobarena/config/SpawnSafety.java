package cloud.mineplay.mobarena.config;

import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.util.BoundingBox;

/** Checks a standing position against real block collision shapes, including partial blocks. */
public final class SpawnSafety {
    private static final double HALF_WIDTH = .3;
    private static final double HEIGHT = 1.8;
    private static final double EPSILON = 1.0e-6;
    private static final double SUPPORT_DEPTH = .05;

    private SpawnSafety() {}

    /** Returns null for a safe standing position, otherwise a short, actionable explanation. */
    public static String problem(Location location) {
        if (location == null || location.getWorld() == null) return "world is not loaded";
        try {
            location.checkFinite();
        } catch (IllegalArgumentException exception) {
            return "location contains invalid coordinates";
        }
        World world = location.getWorld();
        double x = location.getX(), y = location.getY(), z = location.getZ();
        if (Math.abs(x) >= 30_000_000 || Math.abs(z) >= 30_000_000)
            return "location is outside the world coordinate limit";
        if (y < world.getMinHeight() || y + HEIGHT > world.getMaxHeight())
            return "standing space is outside the world's build height";

        BoundingBox body = new BoundingBox(x - HALF_WIDTH + EPSILON, y + EPSILON,
            z - HALF_WIDTH + EPSILON, x + HALF_WIDTH - EPSILON, y + HEIGHT - EPSILON,
            z + HALF_WIDTH - EPSILON);
        BoundingBox support = new BoundingBox(body.getMinX(), y - SUPPORT_DEPTH, body.getMinZ(),
            body.getMaxX(), y + EPSILON, body.getMaxZ());
        BoundingBox contact = body.clone().union(support);
        boolean supported = false;

        // The block below may extend above its own cell (for example, fences and walls).
        int minY = Math.max(world.getMinHeight(), (int) Math.floor(y) - 1);
        int maxY = Math.min(world.getMaxHeight() - 1, (int) Math.floor(body.getMaxY()));
        for (int blockX = (int) Math.floor(body.getMinX()); blockX <= (int) Math.floor(body.getMaxX()); blockX++) {
            for (int blockZ = (int) Math.floor(body.getMinZ()); blockZ <= (int) Math.floor(body.getMaxZ()); blockZ++) {
                for (int blockY = minY; blockY <= maxY; blockY++) {
                    Block block = world.getBlockAt(blockX, blockY, blockZ);
                    BoundingBox cell = new BoundingBox(blockX, blockY, blockZ, blockX + 1, blockY + 1, blockZ + 1);
                    if (hazard(block.getType()) && cell.overlaps(contact))
                        return "touches " + describe(block) + "; choose a harmless standing surface";
                    if (cell.overlaps(body) && (block.isLiquid()
                        || block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()))
                        return "standing space contains liquid at " + coordinates(block) + "; choose a dry location";

                    // Bukkit collision boxes are block-relative; getBoundingBox() instead gives an
                    // outline, which cannot represent the separate empty/solid parts of stairs.
                    for (BoundingBox relative : block.getCollisionShape().getBoundingBoxes()) {
                        BoundingBox shape = relative.clone().shift(blockX, blockY, blockZ);
                        if (shape.overlaps(body)) {
                            String area = shape.getMinY() < y + 1 ? "feet/body" : "head";
                            return area + " obstructed by " + describe(block) + "; stand in clear space";
                        }
                        if (shape.getMaxY() <= y + EPSILON && shape.overlaps(support)) supported = true;
                    }
                }
            }
        }
        return supported ? null : "no supporting surface under your feet; stand on the floor";
    }

    private static String describe(Block block) {
        return block.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " at " + coordinates(block);
    }

    private static String coordinates(Block block) {
        return block.getX() + ", " + block.getY() + ", " + block.getZ();
    }

    private static boolean hazard(Material material) {
        return switch (material) {
            case FIRE, SOUL_FIRE, LAVA, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE, CACTUS, SWEET_BERRY_BUSH,
                POWDER_SNOW, WITHER_ROSE, END_PORTAL, NETHER_PORTAL -> true;
            default -> false;
        };
    }
}
