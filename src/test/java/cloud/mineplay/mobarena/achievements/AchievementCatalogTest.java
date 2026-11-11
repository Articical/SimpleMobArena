package cloud.mineplay.mobarena.achievements;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.co.mineplay.achievements.api.AchievementDefinition;
import uk.co.mineplay.achievements.api.Rarity;

import static org.junit.jupiter.api.Assertions.*;

class AchievementCatalogTest {
    @TempDir Path directory;

    @Test void packagedCatalogueHasAllSupportedMobSourcesAndOrderedOwnNamespaceMilestones() throws Exception {
        AchievementCatalog catalog = AchievementCatalog.load(Path.of("src/main/resources/achievements.yml").toFile());
        assertTrue(catalog.enabled());
        assertEquals(33, catalog.sources().size());
        assertEquals(101, catalog.definitions().size());
        assertEquals(28, AchievementCatalog.mobTypes().size());
        assertEquals(33, new HashSet<>(catalog.sources().stream().map(source -> source.id()).toList()).size());
        Set<String> registered = new HashSet<>();
        for (AchievementDefinition definition : catalog.definitions()) {
            assertEquals("SimpleMobArena", definition.owner());
            assertTrue(definition.id().startsWith("mineplay_mobarena:"));
            assertTrue(definition.progressSource().startsWith("mineplay_mobarena:"));
            assertTrue(registered.containsAll(definition.prerequisites()), definition.id());
            if (definition.parent() != null) assertTrue(registered.contains(definition.parent()), definition.id());
            assertTrue(definition.rewards().isEmpty());
            assertNull(definition.prefixUnlock());
            assertTrue(registered.add(definition.id()));
        }
        for (String type : AchievementCatalog.mobTypes()) {
            String local = type.toLowerCase(java.util.Locale.ROOT);
            assertEquals(List.of(25L, 100L, 500L), catalog.definitions().stream()
                .filter(definition -> definition.progressSource().equals(AchievementCatalog.id(local + "_kills")))
                .map(AchievementDefinition::threshold).toList());
        }
        assertEquals("Kill 25 endermen in the arena.", byId(catalog,"enderman/hunter").description());
        assertEquals("Kill 25 witches in the arena.", byId(catalog,"witch/hunter").description());
        assertThrows(UnsupportedOperationException.class, () -> catalog.definitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.sources().clear());
    }

    @Test void customNumericMilestonesUseOwnSourceRevisionAndDependencyOrder() throws Exception {
        AchievementCatalog catalog = read("""
            revision: 7
            announce: false
            achievements:
              later:
                name: The Next Step
                source: zombie_kills
                target: 500
                rarity: EPIC
                points: 0
                prerequisites: [earlier]
                parent: earlier
                revision: 8
              earlier:
                name: Start Here
                source: zombie_kills
                target: 5
                rarity: UNCOMMON
            """);
        assertEquals(List.of("mineplay_mobarena:earlier", "mineplay_mobarena:later"),
            catalog.definitions().stream().map(AchievementDefinition::id).toList());
        AchievementDefinition earlier = catalog.definitions().getFirst(), later = catalog.definitions().getLast();
        assertEquals(7, earlier.revision());
        assertEquals(Rarity.UNCOMMON.defaultPoints(), earlier.points());
        assertFalse(earlier.announce());
        assertEquals(8, later.revision());
        assertEquals(0, later.points());
    }

    @Test void disablingCatalogueIsExplicitAndDoesNotRequireDefinitions() throws Exception {
        AchievementCatalog catalog = read("enabled: false\n");
        assertFalse(catalog.enabled());
        assertTrue(catalog.definitions().isEmpty());
        assertEquals(33, catalog.sources().size());
        assertThrows(IllegalArgumentException.class, () -> read("enabled: 'false'\n"));
        assertThrows(IllegalArgumentException.class, () -> read("enabled: true\n"));
    }

    @Test void templatesAllowSelectedMobsButDoNotAddUnsupportedSources() throws Exception {
        AchievementCatalog catalog = read("""
            mob-types: [ZOMBIE, LLAMA]
            mob-kill-tiers:
              starter: {name: '{mob} Starter', target: 10, points: 0}
              veteran: {name: '{mob} Veteran', target: 50}
            """);
        assertEquals(4, catalog.definitions().size());
        assertEquals("Llama Starter", byId(catalog,"llama/starter").displayName());
        assertEquals(List.of("mineplay_mobarena:llama/starter"), byId(catalog,"llama/veteran").prerequisites());
        for (String types : List.of("[DROWNED]", "[PLAYER]", "[ZOMBIE, zombie]", "false")) {
            assertThrows(IllegalArgumentException.class, () -> read("mob-types: " + types
                + "\nmob-kill-tiers:\n  starter: {name: Starter, target: 1}\n"));
        }
    }

    @Test void decreasingTargetsDuplicateIdsAndUnknownDependenciesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> read("""
            mob-types: [ZOMBIE]
            mob-kill-tiers:
              first: {name: First, target: 100}
              next: {name: Next, target: 50}
            """));
        assertThrows(IllegalArgumentException.class, () -> read("""
            mob-types: [ZOMBIE]
            achievements:
              zombie/first: {name: Duplicate, source: zombie_kills, target: 1}
            mob-kill-tiers:
              first: {name: First, target: 10}
            """));
        assertThrows(IllegalArgumentException.class, () -> read("""
            achievements:
              first: {name: First, source: total_kills, target: 1, prerequisites: [missing]}
            """));
        assertThrows(IllegalArgumentException.class, () -> read("""
            achievements:
              first: {name: First, source: total_kills, target: 1, parent: second}
              second: {name: Second, source: total_kills, target: 2, parent: first}
            """));
    }

    @Test void foreignSourcesIdentifiersAndInvalidScalarValuesCannotRegister() throws Exception {
        for (String row : List.of(
                "{name: Test, source: fishing:catches, target: 1}",
                "{name: Test, source: matches_won, target: 1}",
                "{name: Test, source: total_kills, target: '10'}",
                "{name: Test, source: total_kills, target: 0}",
                "{name: Test, source: total_kills, target: 1.5}",
                "{name: Test, source: total_kills, target: 1, points: -1}",
                "{name: Test, source: total_kills, target: 1, revision: 0}",
                "{name: Test, source: total_kills, target: 1, rarity: impossible}",
                "{name: Test, source: total_kills, target: 1, icon: not_a_namespaced_id}",
                "{name: Test, source: total_kills, target: 1, prerequisites: [foreign:entry]}")) {
            assertThrows(IllegalArgumentException.class, () -> read("achievements:\n  test: " + row + "\n"), row);
        }
        assertThrows(IllegalArgumentException.class, () -> read("achievements: []\n"));
        assertThrows(IllegalArgumentException.class, () -> read("achievements:\n  foreign:entry: {name: Test, source: total_kills, target: 1}\n"));
        assertThrows(IllegalArgumentException.class, () -> AchievementCatalog.id("bad//id"));
    }

    @Test void fileLoaderRejectsDuplicateKeysAndMalformedDocuments() throws Exception {
        Path file = directory.resolve("achievements.yml");
        Files.writeString(file,"enabled: true\nenabled: false\n");
        assertThrows(IllegalArgumentException.class, () -> AchievementCatalog.load(file.toFile()));
        Files.writeString(file,"enabled: [\n");
        assertThrows(IllegalArgumentException.class, () -> AchievementCatalog.load(file.toFile()));
        Files.writeString(file,"- not a catalogue\n");
        assertThrows(IllegalArgumentException.class, () -> AchievementCatalog.load(file.toFile()));
        Files.writeString(file,"recursive: &loop {child: *loop}\n");
        assertThrows(IllegalArgumentException.class, () -> AchievementCatalog.load(file.toFile()));
    }

    private static AchievementCatalog read(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return AchievementCatalog.read(yaml);
    }

    private static AchievementDefinition byId(AchievementCatalog catalog, String local) {
        return catalog.definitions().stream().filter(definition -> definition.id().equals(AchievementCatalog.id(local)))
            .findFirst().orElseThrow();
    }
}
