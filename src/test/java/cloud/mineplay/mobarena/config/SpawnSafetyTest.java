package cloud.mineplay.mobarena.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class SpawnSafetyTest {
    private World world;
    private final Map<String, Block> blocks = new HashMap<>();

    @BeforeEach void setup() {
        world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int x = invocation.getArgument(0), y = invocation.getArgument(1), z = invocation.getArgument(2);
            return blocks.computeIfAbsent(key(x, y, z), ignored -> block(x, y, z, Material.AIR));
        });
    }

    @Test void fullBlockFloorIsSafeAtNonzeroWorldCoordinates() {
        put(10, 63, -11, Material.STONE, box(0, 0, 0, 1, 1, 1));
        assertNull(SpawnSafety.problem(new Location(world, 10.5, 64, -10.5)));
    }

    @Test void lowerSlabSupportsAPlayerStandingInsideItsBlockCell() {
        put(0, 64, 0, Material.STONE_SLAB, box(0, 0, 0, 1, .5, 1));
        assertNull(SpawnSafety.problem(at(.5, 64.5, .5)));
    }

    @Test void carpetDoesNotBecomeAFalseFeetObstruction() {
        put(0, 63, 0, Material.STONE, box(0, 0, 0, 1, 1, 1));
        put(0, 64, 0, Material.WHITE_CARPET, box(0, 0, 0, 1, .0625, 1));
        assertNull(SpawnSafety.problem(at(.5, 64.0625, .5)));
    }

    @Test void soulSandSupportsTheActualLowerStandingHeight() {
        put(0, 64, 0, Material.SOUL_SAND, box(0, 0, 0, 1, .875, 1));
        assertNull(SpawnSafety.problem(at(.5, 64.875, .5)));
        assertTrue(SpawnSafety.problem(at(.5, 65, .5)).contains("no supporting surface"));
    }

    @Test void lowerStairStepUsesTheDetailedShapeRatherThanItsFullOutline() {
        put(0, 64, 0, Material.STONE_STAIRS, box(0, 0, 0, 1, .5, 1), box(.5, .5, 0, 1, 1, 1));
        assertNull(SpawnSafety.problem(at(.2, 64.5, .5)));
        assertTrue(SpawnSafety.problem(at(.5, 64.5, .5)).contains("feet/body obstructed"));
        assertNull(SpawnSafety.problem(at(.8, 65, .5)));
    }

    @Test void openAirCannotServeAsAFloor() {
        assertTrue(SpawnSafety.problem(at(.5, 64, .5)).contains("stand on the floor"));
    }

    @Test void floatingAboveASlabIsNotAStandingLocation() {
        put(0, 63, 0, Material.STONE_SLAB, box(0, 0, 0, 1, .5, 1));
        assertTrue(SpawnSafety.problem(at(.5, 64, .5)).contains("no supporting surface"));
    }

    @Test void tinyPositionRoundingAboveTheFloorIsTolerated() {
        put(0, 63, 0, Material.STONE, box(0, 0, 0, 1, 1, 1));
        assertNull(SpawnSafety.problem(at(.5, 64.000001, .5)));
    }

    @Test void feetAndHeadObstructionsGiveDifferentUsefulReasons() {
        put(0, 63, 0, Material.STONE, box(0, 0, 0, 1, 1, 1));
        put(0, 65, 0, Material.STONE, box(0, 0, 0, 1, 1, 1));
        assertTrue(SpawnSafety.problem(at(.5, 64, .5)).startsWith("head obstructed by stone at 0, 65, 0"));
        put(0, 64, 0, Material.STONE, box(0, 0, 0, 1, 1, 1));
        assertTrue(SpawnSafety.problem(at(.5, 64, .5)).startsWith("feet/body obstructed"));
    }

    @Test void neighboringWallClippingThePlayersWidthIsRejected() {
        put(0, 63, 0, Material.STONE, box(0, 0, 0, 1, 1, 1));
        put(1, 64, 0, Material.STONE, box(0, 0, 0, 1, 1, 1));
        assertTrue(SpawnSafety.problem(at(.9, 64, .5)).contains("feet/body obstructed"));
        assertNull(SpawnSafety.problem(at(.7, 64, .5)));
    }

    @Test void tallFenceShapeBelowTheFeetStillBlocksTheBody() {
        put(0, 63, 0, Material.OAK_FENCE, box(.375, 0, .375, .625, 1.5, .625));
        assertTrue(SpawnSafety.problem(at(.5, 64, .5)).contains("feet/body obstructed"));
        assertNull(SpawnSafety.problem(at(.5, 64.5, .5)));
    }

    @Test void hazardousFloorAndPassableFireAreRejected() {
        put(0, 63, 0, Material.MAGMA_BLOCK, box(0, 0, 0, 1, 1, 1));
        assertTrue(SpawnSafety.problem(at(.5, 64, .5)).contains("magma block"));
        put(0, 63, 0, Material.STONE, box(0, 0, 0, 1, 1, 1));
        put(0, 64, 0, Material.FIRE);
        assertTrue(SpawnSafety.problem(at(.5, 64, .5)).contains("touches fire"));
    }

    @Test void liquidInTheStandingSpaceIsRejected() {
        put(0, 63, 0, Material.STONE, box(0, 0, 0, 1, 1, 1));
        Block water = put(0, 64, 0, Material.WATER);
        when(water.isLiquid()).thenReturn(true);
        assertTrue(SpawnSafety.problem(at(.5, 64, .5)).contains("contains liquid"));
    }

    @Test void waterloggedLowerSlabIsNotDryUntilAboveTheWaterLevel() {
        Block slab = put(0, 64, 0, Material.STONE_SLAB, box(0, 0, 0, 1, .5, 1));
        Waterlogged waterlogged = mock(Waterlogged.class);
        when(waterlogged.isWaterlogged()).thenReturn(true);
        when(slab.getBlockData()).thenReturn(waterlogged);
        assertTrue(SpawnSafety.problem(at(.5, 64.5, .5)).contains("contains liquid"));
        // A full-height waterlogged support can be stood on without touching its water.
        when(slab.getCollisionShape().getBoundingBoxes()).thenReturn(List.of(box(0, 0, 0, 1, 1, 1)));
        assertNull(SpawnSafety.problem(at(.5, 65, .5)));
    }

    @Test void missingWorldInvalidCoordinatesAndBuildHeightAreExplained() {
        assertEquals("world is not loaded", SpawnSafety.problem(null));
        assertEquals("world is not loaded", SpawnSafety.problem(new Location(null, 0, 64, 0)));
        assertTrue(SpawnSafety.problem(at(Double.NaN, 64, 0)).contains("invalid coordinates"));
        assertTrue(SpawnSafety.problem(at(1.0e20, 64, 0)).contains("coordinate limit"));
        assertTrue(SpawnSafety.problem(at(.5, 319, .5)).contains("build height"));
        assertTrue(SpawnSafety.problem(at(.5, -65, .5)).contains("build height"));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    private Location at(double x, double y, double z) { return new Location(world, x, y, z); }

    private Block put(int x, int y, int z, Material material, BoundingBox... shapes) {
        Block block = block(x, y, z, material, shapes);
        blocks.put(key(x, y, z), block);
        return block;
    }

    private Block block(int x, int y, int z, Material material, BoundingBox... shapes) {
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getType()).thenReturn(material);
        VoxelShape shape = mock(VoxelShape.class);
        when(shape.getBoundingBoxes()).thenReturn(List.of(shapes));
        when(block.getCollisionShape()).thenReturn(shape);
        return block;
    }

    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }
    private static BoundingBox box(double x, double y, double z, double maxX, double maxY, double maxZ) {
        return new BoundingBox(x, y, z, maxX, maxY, maxZ);
    }
}
