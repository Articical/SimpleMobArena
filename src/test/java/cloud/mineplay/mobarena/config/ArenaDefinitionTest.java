package cloud.mineplay.mobarena.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class ArenaDefinitionTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<SpawnSafety> safety;
    private MockedStatic<WorldGuardSupport> worldGuard;
    private World world;
    private YamlConfiguration config;
    private YamlConfiguration defaults;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        safety = mockStatic(SpawnSafety.class);
        worldGuard = mockStatic(WorldGuardSupport.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        config = new YamlConfiguration();
        config.set("world", "world");
        config.set("pos1", "world,0,63,0");
        config.set("pos2", "world,10,70,10");
        config.set("player-spawns", List.of("world,2.5,64.5,2.5"));
        config.set("mob-spawns", List.of("world,4.5,64.5,4.5"));
        config.set("exit", "world,12.5,64.5,2.5");
        defaults = new YamlConfiguration();
        defaults.set("waves.mobs.ZOMBIE.unlock-wave", 1);
        defaults.set("waves.mobs.ZOMBIE.weight", 1);
    }

    @AfterEach void cleanup() {
        worldGuard.close();
        if (!safety.isClosed()) safety.close();
        bukkit.close();
    }

    @Test void validDefinitionAcceptsOmittedOutsiderOverrideAndValidatesNormalExit() {
        ArenaDefinition definition = definition();
        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertFalse(definition.config.contains("outsider-exit"));
        assertEquals(new Location(world, 12.5, 64.5, 2.5), definition.exit);
        safety.verify(() -> SpawnSafety.problem(definition.exit));
        worldGuard.verify(() -> WorldGuardSupport.validate(definition));
    }

    @Test void stalePlayerSpawnReportsItsIndexActualCoordinatesYRangeAndReplacementCommand() {
        config.set("player-spawns", List.of("world,2.5,64.5,2.5", "world,0.5,181,0.5"));
        ArenaDefinition definition = definition();
        String error = errorStarting(definition, "Player spawn #2");
        assertTrue(error.contains("world (0.50, 181.00, 0.50)"), error);
        assertTrue(error.contains("Y must be in blocks 63..70"), error);
        assertFalse(error.contains("X must"), error);
        assertFalse(error.contains("Z must"), error);
        assertTrue(error.contains("/ma admin setplayer spawn"), error);
        assertTrue(error.contains("replace this spawn list"), error);
    }

    @Test void staleMobSpawnReportsBothHorizontalAxesAndItsOwnReplacementCommand() {
        config.set("mob-spawns", List.of("world,4.5,64.5,4.5", "world,-10.5,64.5,130"));
        String error = errorStarting(definition(), "Mob spawn #2");
        assertTrue(error.contains("X must be in blocks 0..10"), error);
        assertTrue(error.contains("Z must be in blocks 0..10"), error);
        assertFalse(error.contains("Y must"), error);
        assertTrue(error.contains("/ma admin setmob spawn"), error);
    }

    @Test void cornersAtTheSameHeightExplainHowToSelectTheUpperCorner() {
        config.set("pos2", "world,10,63,10");
        String error = errorStarting(definition(), "Boundary corners are both at Y=63");
        assertTrue(error.contains("opposite corner above the arena"), error);
        assertTrue(error.contains("/ma admin setpos2 spawn"), error);
    }

    @Test void malformedSpawnDoesNotShiftTheReportedIndicesOfLaterEntries() {
        config.set("player-spawns", List.of("not a location", "world,0.5,181,0.5"));
        ArenaDefinition definition = definition();
        assertTrue(errorStarting(definition, "Player spawn #1").contains("is invalid"));
        assertTrue(errorStarting(definition, "Player spawn #2").contains("Y must be in blocks 63..70"));
    }

    @Test void outsiderOverrideInsideArenaPointsToTheShareExitRepairCommand() {
        config.set("outsider-exit", "world,2.5,64.5,2.5");
        String error = errorStarting(definition(), "Outsider exit:");
        assertTrue(error.contains("location is inside the arena"), error);
        assertTrue(error.contains("/ma admin setoutsider spawn exit"), error);
    }

    @Test void outsiderOverrideInAnUnloadedWorldPointsToTheSameRepairCommand() {
        config.set("outsider-exit", "missing-world,12.5,64.5,2.5");
        String error = errorStarting(definition(), "Outsider exit:");
        assertTrue(error.contains("world is not loaded"), error);
        assertTrue(error.contains("/ma admin setoutsider spawn exit"), error);
    }

    @Test void unsafeSpawnIncludesTheDetailedSafetyReasonAndRepairCommand() {
        safety.when(() -> SpawnSafety.problem(any(Location.class)))
            .thenReturn("head obstructed by stone at 2, 66, 2; stand in clear space");
        String error = errorStarting(definition(), "Player spawn #1");
        assertTrue(error.contains("head obstructed by stone at 2, 66, 2"), error);
        assertTrue(error.contains("/ma admin setplayer spawn"), error);
    }

    @Test void partialBlockStandingPositionsAreAcceptedThroughTheRealDefinitionValidator() {
        safety.close();
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        VoxelShape air = mock(VoxelShape.class);
        when(air.getBoundingBoxes()).thenReturn(List.of());
        VoxelShape slab = mock(VoxelShape.class);
        when(slab.getBoundingBoxes()).thenReturn(List.of(new BoundingBox(0, 0, 0, 1, .5, 1)));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            Block block = mock(Block.class);
            int y = invocation.getArgument(1);
            when(block.getType()).thenReturn(y == 64 ? Material.STONE_SLAB : Material.AIR);
            when(block.getCollisionShape()).thenReturn(y == 64 ? slab : air);
            return block;
        });
        ArenaDefinition definition = definition();
        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertTrue(ArenaDefinition.safe(definition.playerSpawns.getFirst()));
        assertTrue(ArenaDefinition.safe(definition.mobSpawns.getFirst()));
        assertTrue(ArenaDefinition.safe(definition.exit));
    }

    @Test void retiredMobRulesCannotEnterAnyWavePoolAndDoNotRewriteConfiguration() {
        for (String type : List.of("CREEPER", "DROWNED")) {
            defaults.set("waves.mobs." + type + ".unlock-wave", 1);
            defaults.set("waves.mobs." + type + ".weight", 10000);
        }
        String original = defaults.saveToString();
        ArenaDefinition definition = definition();
        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals(List.of(EntityType.ZOMBIE), definition.mobs.stream().map(ArenaDefinition.MobRule::type).toList());
        assertEquals(Set.of(EntityType.ZOMBIE), eligible(definition, 10000));
        assertEquals(original, defaults.saveToString(), "Loading must preserve the administrator's source configuration.");
    }

    @Test void malformedRetiredMobSettingsAreIgnoredRegardlessOfCase() {
        for (String type : List.of("cReEpEr", "dRoWnEd")) {
            String path = "waves.mobs." + type;
            defaults.set(path + ".unlock-wave", "not a number");
            defaults.set(path + ".weight", -1);
            defaults.set(path + ".health-multiplier", Double.NaN);
            defaults.set(path + ".baby-chance", 2);
            defaults.set(path + ".chicken-jockey-chance", -1);
            defaults.set(path + ".equipment.invalid_slot", List.of(Map.of("material", "NOT_A_MATERIAL", "chance", 5)));
        }
        String original = defaults.saveToString();
        ArenaDefinition definition = definition();
        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals(List.of(EntityType.ZOMBIE), definition.mobs.stream().map(ArenaDefinition.MobRule::type).toList());
        assertEquals(original, defaults.saveToString(), "Ignored legacy rules must remain unchanged in the source YAML.");
    }

    @Test void retiredMobEntriesAreIgnoredEvenWhenTheyAreNotConfigurationSections() {
        defaults.set("waves.mobs.creeper", "disabled");
        defaults.set("waves.mobs.drowned", false);
        String original = defaults.saveToString();

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals(List.of(EntityType.ZOMBIE), definition.mobs.stream().map(ArenaDefinition.MobRule::type).toList());
        assertEquals(original, defaults.saveToString());
    }

    @Test void onlyRetiredMobsUnlockingAtWaveOneStillRequiresAnActiveStarterMob() {
        defaults.set("waves.mobs.ZOMBIE.unlock-wave", 2);
        defaults.set("waves.mobs.CREEPER.unlock-wave", 1);
        defaults.set("waves.mobs.DROWNED.unlock-wave", 1);
        ArenaDefinition definition = definition();
        assertEquals(1, definition.errors.size());
        assertTrue(definition.errors.getFirst().contains("No melee mob is available at wave 1"));
        assertTrue(definition.errors.getFirst().contains("waves.mobs.ZOMBIE.unlock-wave to 1"));
        assertTrue(eligible(definition, 1).isEmpty());
        assertEquals(List.of(EntityType.ZOMBIE), definition.mobs.stream().map(ArenaDefinition.MobRule::type).toList());
    }

    @Test void ignoringRetiredMobRulesDoesNotHideOtherUnsupportedMobNames() {
        defaults.set("waves.mobs.CREEPER_OLD.unlock-wave", 1);
        defaults.set("waves.mobs.DROWNED_OLD.unlock-wave", 1);
        ArenaDefinition definition = definition();
        assertTrue(definition.errors.contains("Unsupported or invalid mob: CREEPER_OLD"));
        assertTrue(definition.errors.contains("Unsupported or invalid mob: DROWNED_OLD"));
    }

    @Test void packagedDefaultConfigurationContainsNoRetiredMobRules() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            YamlConfiguration packaged = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertFalse(packaged.contains("waves.mobs.CREEPER"));
            assertFalse(packaged.contains("waves.mobs.DROWNED"));
            assertTrue(packaged.contains("waves.mobs.ZOMBIE"));
            assertEquals(3, packaged.getInt("waves.mobs.SKELETON.unlock-wave"));
        }
    }

    @Test void rangedCapableMobsAreExcludedFromBothOpeningWavesAndAvailableFromWaveThree() {
        Set<EntityType> ranged = Set.of(EntityType.SKELETON, EntityType.STRAY,
            EntityType.BLAZE, EntityType.PILLAGER, EntityType.WITCH, EntityType.WITHER_SKELETON);
        for (EntityType type : ranged) defaults.set("waves.mobs." + type.name() + ".unlock-wave", 1);

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals(Set.of(EntityType.ZOMBIE), eligible(definition, 1));
        assertEquals(Set.of(EntityType.ZOMBIE), eligible(definition, 2));
        assertTrue(eligible(definition, 3).containsAll(ranged));
    }

    @Test void configuredLaterUnlocksRemainLaterThanTheRangedFloor() {
        defaults.set("waves.mobs.SKELETON.unlock-wave", 5);
        defaults.set("waves.mobs.BLAZE.unlock-wave", 8);
        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertFalse(eligible(definition, 3).contains(EntityType.SKELETON));
        assertFalse(eligible(definition, 4).contains(EntityType.SKELETON));
        assertTrue(eligible(definition, 5).contains(EntityType.SKELETON));
        assertFalse(eligible(definition, 7).contains(EntityType.BLAZE));
        assertTrue(eligible(definition, 8).contains(EntityType.BLAZE));
    }

    @Test void rangedOnlyLegacyConfigIsPreservedAndExplainsHowToAddAMeleeStarter() {
        defaults.set("waves.mobs.ZOMBIE", null);
        defaults.set("waves.mobs.SKELETON.unlock-wave", 1);
        defaults.set("waves.mobs.DROWNED.unlock-wave", 2);
        String original = defaults.saveToString();

        ArenaDefinition definition = definition();

        assertEquals(original, defaults.saveToString(), "Loading must not rewrite the administrator's legacy configuration");
        assertTrue(eligible(definition, 1).isEmpty());
        assertEquals(Set.of(EntityType.SKELETON), eligible(definition, 3));
        assertEquals(1, definition.errors.size());
        String error = definition.errors.getFirst();
        assertTrue(error.contains("No melee mob is available at wave 1"));
        assertTrue(error.contains("waves.mobs.ZOMBIE.unlock-wave to 1 in config.yml"));
        assertTrue(error.contains("/ma admin reload"));
        assertTrue(error.contains("wave 3 or later"));
    }

    @Test void legacyConfigurationReceivesHealthScalingDefaultsWithoutBeingRewritten() {
        String original = defaults.saveToString();
        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertTrue(definition.playerHealthScaling);
        assertEquals(.15, definition.healthPerAdditionalPlayer);
        assertEquals(2.5, definition.maxPlayerHealthMultiplier);
        assertEquals(original, defaults.saveToString());
    }

    @Test void customHealthScalingCanBeDisabledAndRetainsItsConfiguredNumbers() {
        defaults.set("waves.player-health-scaling.enabled", false);
        defaults.set("waves.player-health-scaling.additional-player-multiplier", .4);
        defaults.set("waves.player-health-scaling.maximum-multiplier", 3);

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertFalse(definition.playerHealthScaling);
        assertEquals(.4, definition.healthPerAdditionalPlayer);
        assertEquals(3, definition.maxPlayerHealthMultiplier);
    }

    @Test void partiallyConfiguredHealthScalingDefaultsOnlyTheMissingFields() {
        defaults.set("waves.player-health-scaling.additional-player-multiplier", 0);

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertTrue(definition.playerHealthScaling);
        assertEquals(0, definition.healthPerAdditionalPlayer);
        assertEquals(2.5, definition.maxPlayerHealthMultiplier);
    }

    @Test void healthScalingEnabledRequiresAnActualBoolean() {
        String path = "waves.player-health-scaling.enabled";
        for (Object invalid : List.of("true", "false", "maybe", 1, .5, List.of(true))) {
            defaults.set(path, invalid);
            ArenaDefinition definition = definition();
            assertEquals(1, definition.errors.size(), () -> invalid + ": " + definition.errors);
            assertTrue(definition.errors.getFirst().contains(path), () -> definition.errors.toString());
            assertTrue(definition.playerHealthScaling, "Invalid settings should retain the safe default while blocking the arena.");
        }
    }

    @Test void malformedHealthScalingSectionIsNotSilentlyTreatedAsMissing() {
        for (Object invalid : List.of(false, "disabled", 3, List.of(.15))) {
            defaults.set("waves.player-health-scaling", invalid);
            ArenaDefinition definition = definition();
            assertEquals(1, definition.errors.size(), () -> invalid + ": " + definition.errors);
            assertTrue(definition.errors.getFirst().contains("waves.player-health-scaling must be a YAML configuration section"));
        }
    }

    @Test void healthScalingNumbersRejectWrongTypesNonfiniteValuesAndOutOfRangeValuesEvenWhenDisabled() {
        defaults.set("waves.player-health-scaling.enabled", false);
        for (String suffix : List.of("additional-player-multiplier", "maximum-multiplier")) {
            String path = "waves.player-health-scaling." + suffix;
            for (Object invalid : List.of("0.15", true, List.of(.15), Double.NaN,
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -.01, 20.01)) {
                defaults.set(path, invalid);
                ArenaDefinition definition = definition();
                assertEquals(1, definition.errors.size(), () -> path + "=" + invalid + ": " + definition.errors);
                assertTrue(definition.errors.getFirst().contains(path), () -> definition.errors.toString());
                assertEquals(suffix.equals("additional-player-multiplier") ? .15 : 2.5,
                    suffix.equals("additional-player-multiplier") ? definition.healthPerAdditionalPlayer : definition.maxPlayerHealthMultiplier);
            }
            defaults.set(path, null);
        }
        defaults.set("waves.player-health-scaling.additional-player-multiplier", 10.01);
        defaults.set("waves.player-health-scaling.maximum-multiplier", .99);
        assertEquals(2, definition().errors.size());
    }

    @Test void healthScalingAcceptsNumericBoundaryValues() {
        defaults.set("waves.player-health-scaling.additional-player-multiplier", 0);
        defaults.set("waves.player-health-scaling.maximum-multiplier", 1);
        ArenaDefinition minimum = definition();
        assertTrue(minimum.errors.isEmpty(), () -> minimum.errors.toString());
        assertEquals(0, minimum.healthPerAdditionalPlayer);
        assertEquals(1, minimum.maxPlayerHealthMultiplier);

        defaults.set("waves.player-health-scaling.additional-player-multiplier", 10);
        defaults.set("waves.player-health-scaling.maximum-multiplier", 20);
        ArenaDefinition maximum = definition();
        assertTrue(maximum.errors.isEmpty(), () -> maximum.errors.toString());
        assertEquals(10, maximum.healthPerAdditionalPlayer);
        assertEquals(20, maximum.maxPlayerHealthMultiplier);
    }

    private static Set<EntityType> eligible(ArenaDefinition definition, int wave) {
        return definition.mobs.stream().filter(rule -> rule.unlockWave() <= wave).map(ArenaDefinition.MobRule::type).collect(Collectors.toSet());
    }

    private ArenaDefinition definition() { return new ArenaDefinition("spawn", config, defaults); }

    private String errorStarting(ArenaDefinition definition, String prefix) {
        return definition.errors.stream().filter(error -> error.startsWith(prefix)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + prefix + " in " + definition.errors));
    }
}
