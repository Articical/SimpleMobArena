package cloud.mineplay.mobarena.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import io.papermc.paper.registry.RegistryAccess;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
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

    @BeforeAll static void initializeItemIdentities() {
        // Material.isItem resolves through Paper's registry; only identity lookup
        // is needed to validate the inherited equipment maps in these tests.
        try (MockedStatic<RegistryAccess> registryAccess = mockStatic(RegistryAccess.class)) {
            RegistryAccess access = mock(RegistryAccess.class, RETURNS_DEEP_STUBS);
            registryAccess.when(RegistryAccess::registryAccess).thenReturn(access);
            Registry<ItemType> items = Registry.ITEM;
            // Mockito initializes ItemType's server-backed static constants when it
            // creates a mock. A plain identity proxy avoids bootstrapping those constants.
            ItemType identity = (ItemType) java.lang.reflect.Proxy.newProxyInstance(ItemType.class.getClassLoader(),
                    new Class<?>[]{ItemType.class}, (proxy, method, arguments) -> null);
            doReturn(identity).when(items).get(any(NamespacedKey.class));
        }
    }

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
        for (String type : List.of("DROWNED")) {
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
        for (String type : List.of("dRoWnEd")) {
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
        defaults.set("waves.mobs.drowned", false);
        String original = defaults.saveToString();

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals(List.of(EntityType.ZOMBIE), definition.mobs.stream().map(ArenaDefinition.MobRule::type).toList());
        assertEquals(original, defaults.saveToString());
    }

    @Test void onlyRetiredMobsUnlockingAtWaveOneStillRequiresAnActiveStarterMob() {
        defaults.set("waves.mobs.ZOMBIE.unlock-wave", 2);
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

    @Test void packagedDefaultConfigurationRestoresCreepersButStillExcludesDrowned() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            YamlConfiguration packaged = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals(4, packaged.getInt("waves.mobs.CREEPER.unlock-wave"));
            assertFalse(packaged.contains("waves.mobs.DROWNED"));
            assertTrue(packaged.contains("waves.mobs.ZOMBIE"));
            assertEquals(3, packaged.getInt("waves.mobs.SKELETON.unlock-wave"));
        }
    }

    @Test void rangedCapableMobsAreExcludedFromBothOpeningWavesAndAvailableFromWaveThree() {
        Set<EntityType> ranged = Set.of(EntityType.SKELETON, EntityType.STRAY,
            EntityType.BLAZE, EntityType.PILLAGER, EntityType.WITCH, EntityType.WITHER_SKELETON,
            EntityType.SHULKER, EntityType.BREEZE, EntityType.EVOKER, EntityType.PIGLIN,
            EntityType.PHANTOM, EntityType.LLAMA);
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
        assertFalse(definition.memeRounds.enabled(), "Legacy configurations must not gain random themes without opting in.");
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

    @Test void namedPoolReplacesMobTypesAndInheritsGlobalVariants() {
        defaults.set("waves.mobs.SPIDER.unlock-wave", 1);
        defaults.set("waves.mobs.ZOMBIE.baby-chance", .2);
        defaults.set("waves.mobs.ZOMBIE.chicken-jockey-chance", .05);
        defaults.set("waves.mobs.ZOMBIE.health-multiplier", 1.5);
        defaults.set("mob-sets.undead.mobs.ZOMBIE.weight", 7);
        config.set("mob-set", "undead");
        String originalDefaults = defaults.saveToString(), originalArena = config.saveToString();

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals("undead", definition.mobSet);
        assertEquals(List.of(new ArenaDefinition.MobRule(EntityType.ZOMBIE, 1, 7, 1.5)), definition.mobs);
        assertEquals(.2, definition.mobSettings(EntityType.ZOMBIE).getDouble("baby-chance"));
        assertEquals(.05, definition.mobSettings(EntityType.ZOMBIE).getDouble("chicken-jockey-chance"));
        assertNull(definition.mobSettings(EntityType.SPIDER));
        assertEquals(originalDefaults, defaults.saveToString());
        assertEquals(originalArena, config.saveToString());
    }

    @Test void selectedEquipmentListsAreDeepCopiedAndLocalSlotsReplaceInheritedSlots() {
        defaults.set("waves.mobs.ZOMBIE.equipment.hand", List.of(Map.of("material", "STONE_SWORD", "chance", .25)));
        defaults.set("waves.mobs.ZOMBIE.equipment.head", List.of(Map.of("material", "IRON_HELMET", "chance", .1)));
        defaults.set("mob-sets.undead.mobs.ZOMBIE.equipment.hand", List.of());
        defaults.set("mob-sets.undead.mobs.ZOMBIE.weight", 2);
        config.set("mob-set", "undead");
        String original = defaults.saveToString();

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertTrue(definition.mobSettings(EntityType.ZOMBIE).getMapList("equipment.hand").isEmpty());
        assertEquals("IRON_HELMET", definition.mobSettings(EntityType.ZOMBIE).getMapList("equipment.head").getFirst().get("material"));
        definition.mobSettings(EntityType.ZOMBIE).getMapList("equipment.head").getFirst().clear();
        assertEquals(original, defaults.saveToString(), "Resolved equipment must not share mutable entries with source YAML.");
    }

    @Test void localMobPoolReplacesNamedPoolAndStillInheritsGlobalDefaultsForItsRows() {
        defaults.set("mob-sets.undead.mobs.ZOMBIE.weight", 7);
        defaults.set("waves.mobs.SPIDER.health-multiplier", 1.8);
        config.set("mob-set", "undead");
        config.set("waves.mobs.SPIDER.unlock-wave", 1);
        config.set("waves.mobs.SPIDER.weight", 3);

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals(List.of(new ArenaDefinition.MobRule(EntityType.SPIDER, 1, 3, 1.8)), definition.mobs);
        assertNull(definition.mobSettings(EntityType.ZOMBIE));
    }

    @Test void separateArenaPoolsDoNotLeakSettingsIntoOneAnother() {
        defaults.set("waves.mobs.ZOMBIE.baby-chance", .1);
        config.set("waves.mobs.ZOMBIE.baby-chance", .8);
        ArenaDefinition custom = definition();
        config.set("waves", null);
        ArenaDefinition inherited = definition();

        assertEquals(.8, custom.mobSettings(EntityType.ZOMBIE).getDouble("baby-chance"));
        assertEquals(.1, inherited.mobSettings(EntityType.ZOMBIE).getDouble("baby-chance"));
        assertEquals(.1, defaults.getDouble("waves.mobs.ZOMBIE.baby-chance"));
    }

    @Test void missingMalformedAndEmptySelectedPoolsBlockTheArena() {
        config.set("mob-set", "missing");
        assertTrue(definition().errors.stream().anyMatch(error -> error.contains("Unknown or invalid mob-set 'missing'")));
        defaults.set("mob-sets.missing.mobs", List.of("ZOMBIE"));
        assertTrue(definition().errors.stream().anyMatch(error -> error.contains("Unknown or invalid mob-set 'missing'")));
        defaults.createSection("mob-sets.missing.mobs");
        assertTrue(definition().errors.stream().anyMatch(error -> error.contains("No melee mob")));
        config.set("mob-set", "");
        config.set("waves.mobs", "ZOMBIE");
        assertTrue(definition().errors.stream().anyMatch(error -> error.contains("Arena waves.mobs must be")));
    }

    @Test void localOverridesRestoreCreepersExcludeDrownedAndEnforceRangedFloor() {
        config.set("waves.mobs.ZOMBIE.weight", 1);
        config.set("waves.mobs.SKELETON.unlock-wave", 1);
        config.set("waves.mobs.CREEPER.weight", 99);
        config.set("waves.mobs.DROWNED.weight", 99);

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals(Set.of(EntityType.ZOMBIE, EntityType.CREEPER), eligible(definition, 2));
        assertEquals(Set.of(EntityType.ZOMBIE, EntityType.CREEPER, EntityType.SKELETON), eligible(definition, 3));
    }

    @Test void perArenaWaveOverridesInheritMissingFieldsAndKeepLegacyTopLevelPrecedence() {
        defaults.set("waves.base-mobs", 12);
        defaults.set("waves.spawn-batch-size", 3);
        defaults.set("waves.player-health-scaling.maximum-multiplier", 3.5);
        config.set("waves.base-mobs", 8);
        config.set("waves.mobs-per-wave-per-player", 4);
        config.set("waves.max-waves", 25);
        config.set("waves.max-active-mobs", 60);
        config.set("waves.health-increase-percent", 30);
        config.set("waves.heal-between-waves", false);
        config.set("waves.player-health-scaling.additional-player-multiplier", .3);
        config.set("max-waves", 20);
        config.set("max-active-mobs", 50);
        String original = defaults.saveToString();

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals(8, definition.baseMobs);
        assertEquals(4, definition.perWavePerPlayer);
        assertEquals(3, definition.spawnBatchSize);
        assertEquals(20, definition.maxWaves);
        assertEquals(50, definition.maxActiveMobs);
        assertEquals(.3, definition.healthIncrease);
        assertEquals(.3, definition.healthPerAdditionalPlayer);
        assertEquals(3.5, definition.maxPlayerHealthMultiplier);
        assertFalse(definition.healBetweenWaves);
        assertEquals(original, defaults.saveToString());
    }

    @Test void malformedLocalWaveTuningAndMobSettingsAreRejected() {
        config.set("waves.base-mobs", "eight");
        config.set("waves.heal-between-waves", "false");
        config.set("waves.mobs.ZOMBIE.baby-chance", "often");
        config.set("waves.mobs.ZOMBIE.equipment.hand", "STONE_SWORD");

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.stream().anyMatch(error -> error.contains("waves.base-mobs")));
        assertTrue(definition.errors.stream().anyMatch(error -> error.contains("waves.heal-between-waves")));
        assertTrue(definition.errors.stream().anyMatch(error -> error.contains("ZOMBIE.baby-chance")));
        assertTrue(definition.errors.stream().anyMatch(error -> error.contains("ZOMBIE.equipment.hand")));
    }

    @Test void duelsRequireTwoPlayersAndNoMobConfiguration() {
        duel("DUEL");
        config.set("min-players", "obsolete");
        config.set("max-players", 64);
        config.set("mob-set", "missing");
        config.set("mob-spawns", List.of("not a location"));
        config.set("waves", "unused");
        defaults.set("waves", "unused");

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals(ArenaMode.DUEL, definition.mode);
        assertTrue(definition.mode.isDuel());
        assertEquals(2, definition.minPlayers);
        assertEquals(2, definition.maxPlayers);
        assertEquals(1, definition.teamSize);
        assertEquals(300, definition.duelMaxSeconds);
        assertFalse(definition.chooseTeams);
        assertTrue(definition.mobs.isEmpty());
        assertTrue(definition.mobSpawns.isEmpty());
        assertEquals(List.of(definition.redSpawns.getFirst(), definition.blueSpawns.getFirst()), definition.playerSpawns);
    }

    @Test void teamDuelsDeriveFullLobbySizeAndSupportBothSelectionModes() {
        duel("TEAM_DUEL");
        config.set("duel.team-size", 3);
        config.set("duel.maximum-seconds", 120);
        ArenaDefinition automatic = definition();
        assertTrue(automatic.errors.isEmpty(), () -> automatic.errors.toString());
        assertEquals(6, automatic.minPlayers);
        assertEquals(6, automatic.maxPlayers);
        assertEquals(3, automatic.teamSize);
        assertEquals(120, automatic.duelMaxSeconds);
        assertFalse(automatic.chooseTeams);

        config.set("duel.team-selection", "CHOOSE");
        ArenaDefinition chosen = definition();
        assertTrue(chosen.errors.isEmpty(), () -> chosen.errors.toString());
        assertTrue(chosen.chooseTeams);
    }

    @Test void duelsCanKeepSeparateWaitingSpawns() {
        duel("DUEL");
        config.set("player-spawns", List.of("world,5.5,64.5,5.5"));

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals(List.of(new Location(world, 5.5, 64.5, 5.5)), definition.playerSpawns);
    }

    @Test void duelTeamSpawnsMustExistAndStaySafelyInsideTheArena() {
        duel("TEAM_DUEL");
        config.set("duel.red-spawns", List.of());
        config.set("duel.blue-spawns", List.of("world,15.5,64.5,2.5"));

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.stream().anyMatch(error -> error.startsWith("No red team spawns")));
        assertTrue(definition.errors.stream().anyMatch(error -> error.startsWith("Blue team spawn #1") && error.contains("outside the boundary")));
        config.set("duel.red-spawns", List.of("world,2.5,64.5,2.5"));
        config.set("duel.blue-spawns", List.of("world,8.5,64.5,8.5"));
        safety.when(() -> SpawnSafety.problem(new Location(world, 8.5, 64.5, 8.5))).thenReturn("head obstructed");
        assertTrue(definition().errors.stream().anyMatch(error -> error.startsWith("Blue team spawn #1") && error.contains("head obstructed")));
    }

    @Test void invalidDuelSettingsFailClosed() {
        duel("TEAM_DUEL");
        config.set("duel.team-size", 17);
        config.set("duel.maximum-seconds", 29);
        config.set("duel.team-selection", "randomish");
        ArenaDefinition definition = definition();
        assertTrue(definition.errors.stream().anyMatch(error -> error.contains("duel.team-size")));
        assertTrue(definition.errors.stream().anyMatch(error -> error.contains("duel.maximum-seconds")));
        assertTrue(definition.errors.stream().anyMatch(error -> error.contains("duel.team-selection")));
        config.set("mode", "DUELS");
        assertTrue(definition().errors.stream().anyMatch(error -> error.contains("mode must be")));
    }

    @Test void malformedSpawnListsCannotSilentlyTurnIntoFallbacks() {
        duel("TEAM_DUEL");
        config.set("player-spawns", "world,2.5,64.5,2.5");
        config.set("duel.red-spawns", List.of(12, "world,2.5,64.5,2.5"));
        ArenaDefinition definition = definition();
        assertTrue(definition.errors.contains("player-spawns must be a list of locations."));
        assertTrue(definition.errors.stream().anyMatch(error -> error.startsWith("Red team spawn #1") && error.contains("invalid")));
    }

    @Test void packagedDuelSamplesAreDisabledAndHaveNoInventedCoordinates() throws Exception {
        for (String sample : List.of("duel-example", "team-duel-example")) {
            try (var stream = getClass().getResourceAsStream("/arenas/" + sample + ".yml")) {
                assertNotNull(stream);
                YamlConfiguration packaged = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                assertFalse(packaged.getBoolean("enabled"));
                assertTrue(packaged.getString("pos1", "").isEmpty());
                assertTrue(packaged.getStringList("duel.red-spawns").isEmpty());
                assertTrue(packaged.getStringList("duel.blue-spawns").isEmpty());
                assertEquals(sample.equals("duel-example") ? "DUEL" : "TEAM_DUEL", packaged.getString("mode"));
            }
        }
    }

    @Test void allSupportedMobTypesLoadWithoutChangingSourceSettings() {
        for (EntityType type : fullRoster()) {
            defaults.set("waves.mobs." + type + ".unlock-wave", 1);
            defaults.set("waves.mobs." + type + ".weight", 2);
        }
        String original = defaults.saveToString();

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals(fullRoster(), eligible(definition, 3));
        assertEquals(28, definition.mobs.size());
        assertEquals(original, defaults.saveToString());
    }

    @Test void restoredCreeperRulesAreValidatedInsteadOfSilentlyIgnored() {
        defaults.set("waves.mobs.CREEPER.unlock-wave", 4);
        defaults.set("waves.mobs.CREEPER.weight", -1);
        defaults.set("waves.mobs.CREEPER.equipment.invalid_slot", List.of(Map.of("material", "STONE", "chance", 1)));

        ArenaDefinition definition = definition();

        assertTrue(definition.errors.stream().anyMatch(error -> error.contains("weight must be an integer")));
        assertTrue(definition.errors.stream().anyMatch(error -> error.contains("Unsupported mob equipment slot: invalid_slot")));
        defaults.set("waves.mobs.CREEPER", "disabled");
        assertTrue(definition().errors.contains("Unsupported or invalid mob: CREEPER"));
    }

    @Test void packagedDefaultsProvideTheCompleteRosterWithUsableEquipment() throws Exception {
        YamlConfiguration packaged = resource("/config.yml");
        ArenaDefinition definition = new ArenaDefinition("defaults", config, packaged);

        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertEquals(fullRoster(), eligible(definition, 30));
        assertEquals("CROSSBOW", definition.mobSettings(EntityType.PILLAGER).getMapList("equipment.hand").getFirst().get("material"));
        assertEquals("IRON_SWORD", definition.mobSettings(EntityType.VEX).getMapList("equipment.hand").getFirst().get("material"));
        assertEquals(Set.of(EntityType.ZOMBIE, EntityType.SPIDER), eligible(definition, 1));
        assertTrue(definition.memeRounds.enabled());
        assertEquals(.12, definition.memeRounds.chance());
        assertEquals(3, definition.memeRounds.themes().size());
    }

    @Test void newExamplesAreDisabledIndependentPoolsWithValidTuningOnceGeometryIsSet() throws Exception {
        Set<Set<EntityType>> distinctPools = new java.util.HashSet<>();
        Set<Double> difficulties = new java.util.HashSet<>();
        for (String sample : List.of("training-example", "raiders-example", "nether-example", "sky-example")) {
            YamlConfiguration example = resource("/arenas/" + sample + ".yml");
            assertFalse(example.getBoolean("enabled"));
            assertEquals("MOB_ARENA", example.getString("mode"));
            assertTrue(example.getString("pos1", "").isEmpty());
            assertTrue(example.getString("pos2", "").isEmpty());
            assertTrue(example.getString("exit", "").isEmpty());
            assertTrue(example.getStringList("player-spawns").isEmpty());
            assertTrue(example.getStringList("mob-spawns").isEmpty());
            assertFalse(example.contains("mob-set"));
            // Only geometry is supplied by setup; even an empty global config must
            // leave a usable mob pool, progression and health/count settings.
            for (String key : List.of("world", "pos1", "pos2", "exit", "player-spawns", "mob-spawns"))
                example.set(key, config.get(key));
            ArenaDefinition definition = new ArenaDefinition(sample, example, new YamlConfiguration());
            assertTrue(definition.errors.isEmpty(), () -> sample + ": " + definition.errors);
            assertFalse(eligible(definition, 1).isEmpty());
            assertTrue(definition.mobs.stream().allMatch(mob -> mob.unlockWave() <= definition.maxWaves));
            assertTrue(definition.maxActiveMobs <= 40);
            assertTrue(definition.maxMobsPerWave <= 100);
            assertFalse(eligible(definition, 2).contains(EntityType.PILLAGER));
            assertFalse(eligible(definition, 2).contains(EntityType.PIGLIN));
            assertFalse(eligible(definition, 2).contains(EntityType.LLAMA));
            distinctPools.add(eligible(definition, definition.maxWaves));
            difficulties.add(definition.difficulty);
        }
        assertEquals(4, distinctPools.size());
        assertEquals(4, difficulties.size());
    }

    @Test void suppliedSpawnConfigPreservesAllPositionsAndProvidesTheFullStagedRoster() throws Exception {
        YamlConfiguration supplied = new YamlConfiguration();
        supplied.load(java.nio.file.Path.of("fixed-config", "spawn.yml").toFile());
        assertEquals("world,231.51012187242995,100.0,-458.3027600603243,179.24207,10.349913", supplied.getString("pos1"));
        assertEquals("world,264.64699775505164,109.0,-492.60888607890513,-0.0060424805,-7.9500055", supplied.getString("pos2"));
        assertEquals("world,247.89262434499298,102.0,-446.2987178840691,179.54492,-0.3003199", supplied.getString("exit"));
        assertEquals("world,247.91129765399677,113.0,-453.90064017849227,179.54509,9.749675", supplied.getString("outsider-exit"));
        assertEquals(List.of(
            "world,249.55506082411765,102.0,-460.74666078202176,-179.55597,-1.6501552",
            "world,248.58885105851314,102.0,-460.6543704423471,-179.55592,1.9497892",
            "world,247.51188402587664,102.0,-460.6458412375194,179.54395,0.89970106",
            "world,246.5095577594062,102.0,-460.48574837134476,179.84372,-2.1002946"
        ), supplied.getStringList("player-spawns"));
        assertEquals(List.of(
            "world,234.76448779313063,104.0,-462.9285483517688,-134.85617,5.099676",
            "world,235.45284068750198,102.0,-474.4586064872781,-89.85568,-1.9503525",
            "world,235.48622356633763,102.0,-478.1657212954131,-89.405495,-1.3503505",
            "world,234.5602014655103,104.0,-489.4339395537943,-41.255028,2.2496688",
            "world,246.94660721219103,102.0,-491.6077012296489,-0.7549945,-3.4503248",
            "world,249.53654280910666,102.0,-491.6474554092403,-0.7549945,-3.4503248",
            "world,261.1959885134768,103.0,-489.52518877954407,38.84496,6.149677",
            "world,261.20541715349594,102.0,-475.46828884644793,89.24496,-2.8503184",
            "world,260.53593826566305,103.0,-463.9005333060856,126.145035,2.5496652"
        ), supplied.getStringList("mob-spawns"));
        ArenaDefinition definition = new ArenaDefinition("spawn", supplied, new YamlConfiguration());
        assertTrue(definition.errors.isEmpty(), () -> definition.errors.toString());
        assertTrue(definition.enabled);
        assertEquals(1, definition.minPlayers);
        assertEquals(4, definition.maxPlayers);
        assertEquals(15, definition.countdownSeconds);
        assertEquals(30, definition.maxWaves);
        assertEquals(fullRoster(), eligible(definition, definition.maxWaves));
        assertEquals(Set.of(EntityType.ZOMBIE, EntityType.SPIDER, EntityType.HUSK, EntityType.SILVERFISH), eligible(definition, 2));
        assertTrue(definition.mobs.stream().filter(mob -> mob.type() == EntityType.RAVAGER).allMatch(mob -> mob.unlockWave() == 20 && mob.weight() == 1));
        assertEquals(40, definition.maxActiveMobs);
        assertEquals(100, definition.maxMobsPerWave);
        assertEquals(.10, definition.healthIncrease);
        assertEquals(1.30, definition.maxPlayerHealthMultiplier);
        assertTrue(definition.memeRounds.enabled());
        assertEquals(.12, definition.memeRounds.chance());
        assertEquals(4, definition.memeRounds.minimumWave());
        assertEquals(3, definition.memeRounds.cooldownWaves());
    }

    @Test void localMemeSettingsInheritDefaultsWithoutChangingAnotherArena() {
        defaults.set("waves.meme-rounds.enabled", true);
        defaults.set("waves.meme-rounds.chance", .12);
        defaults.set("waves.meme-rounds.types.goats.name", "Goat Stampede");
        defaults.set("waves.meme-rounds.types.goats.mobs", List.of("GOAT"));
        config.set("waves.meme-rounds.chance", .3);
        String original = defaults.saveToString();
        ArenaDefinition customized = definition();
        assertTrue(customized.errors.isEmpty(), () -> customized.errors.toString());
        assertEquals(.3, customized.memeRounds.chance());
        assertEquals(List.of(EntityType.GOAT), customized.memeRounds.themes().getFirst().mobs());
        config.set("waves.meme-rounds", null);
        assertEquals(.12, definition().memeRounds.chance());
        assertEquals(original, defaults.saveToString());
        config.set("waves.meme-rounds.enabled", false);
        assertFalse(definition().memeRounds.enabled());
    }

    @Test void invalidMemeSettingsFailArenaValidationButAreIgnoredForDuels() {
        config.set("waves.meme-rounds", "random goats");
        assertTrue(definition().errors.stream().anyMatch(error -> error.startsWith("waves.meme-rounds must be")));
        config.set("waves.meme-rounds", null);
        config.set("waves.meme-rounds.enabled", true);
        assertTrue(definition().errors.stream().anyMatch(error -> error.contains("configured types")));
        config.set("waves.meme-rounds.types.drowned.mobs", List.of("DROWNED"));
        assertTrue(definition().errors.contains("waves.meme-rounds: unsupported mob DROWNED."));
        config.set("waves.meme-rounds.types.drowned.mobs", List.of("ENDER_DRAGON"));
        assertTrue(definition().errors.contains("waves.meme-rounds: unsupported mob ENDER_DRAGON."));
        duel("DUEL");
        ArenaDefinition duel = definition();
        assertTrue(duel.errors.isEmpty(), () -> duel.errors.toString());
        assertFalse(duel.memeRounds.enabled());
    }

    private YamlConfiguration resource(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            YamlConfiguration result = new YamlConfiguration();
            result.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return result;
        }
    }

    private static Set<EntityType> fullRoster() {
        return Set.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.SKELETON, EntityType.STRAY,
            EntityType.WITHER_SKELETON, EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.BLAZE,
            EntityType.ENDERMAN, EntityType.SILVERFISH, EntityType.ENDERMITE, EntityType.PILLAGER,
            EntityType.VINDICATOR, EntityType.WITCH, EntityType.ZOMBIFIED_PIGLIN, EntityType.PIGLIN_BRUTE,
            EntityType.CREEPER, EntityType.RAVAGER, EntityType.SHULKER, EntityType.IRON_GOLEM,
            EntityType.BREEZE, EntityType.GOAT, EntityType.PIGLIN, EntityType.PHANTOM, EntityType.EVOKER,
            EntityType.BEE, EntityType.VEX, EntityType.LLAMA);
    }

    private void duel(String mode) {
        config.set("mode", mode);
        config.set("player-spawns", List.of());
        config.set("mob-spawns", null);
        config.set("duel.red-spawns", List.of("world,2.5,64.5,2.5"));
        config.set("duel.blue-spawns", List.of("world,8.5,64.5,8.5"));
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
