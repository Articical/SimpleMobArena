package cloud.mineplay.mobarena.equipment;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClassKitTest {
    @TempDir Path directory;

    private File classes(String source) throws Exception {
        Path path = directory.resolve("classes.yml");
        Files.writeString(path, source);
        return path.toFile();
    }

    @Test void invalidPotionDisablesOnlyItsClassAndRetainsTheExactSettingPath() throws Exception {
        String source = """
                classes:
                  knight:
                    display-name: Knight
                    required-kills: 0
                  scout:
                    display-name: Scout
                    required-kills: 100
                    items:
                      '5': {material: SPLASH_POTION}
                """;
        File file = classes(source);
        ClassKit.LoadResult result = ClassKit.loadAvailable(file);
        assertEquals(Set.of("knight"), result.kits().keySet());
        assertEquals(Set.of("scout"), result.errors().keySet());
        assertTrue(result.errors().get("scout").contains("classes.scout.items.5"));
        assertTrue(result.errors().get("scout").contains("potion section"));
        assertEquals(source, Files.readString(file.toPath()), "Loading must not rewrite the server's kit definitions");
        assertThrows(IllegalArgumentException.class, () -> ClassKit.load(file), "Strict loading remains available for callers that require every class");
    }

    @Test void allInvalidClassesProduceDiagnosticsWithoutAnEquipmentRecoveryFailure() throws Exception {
        ClassKit.LoadResult result = ClassKit.loadAvailable(classes("""
                classes:
                  scout:
                    items:
                      '5': {material: POTION}
                  tank:
                    required-kills: -1
                """));
        assertTrue(result.kits().isEmpty());
        assertEquals(Set.of("scout", "tank"), result.errors().keySet());
    }

    @Test void caseInsensitiveDuplicateIdentifiersDisableEveryDeclarationForThatClass() throws Exception {
        ClassKit.LoadResult result = ClassKit.loadAvailable(classes("""
                classes:
                  knight: {display-name: Knight}
                  Scout: {display-name: First scout}
                  scout:
                    items:
                      '5': {material: POTION}
                  SCOUT: {display-name: Third scout}
                """));
        assertEquals(Set.of("knight"), result.kits().keySet());
        assertEquals(Set.of("Scout", "scout", "SCOUT"), result.errors().keySet());
        result.errors().values().forEach(message -> assertTrue(message.contains("Duplicate class identifier")));
    }

    @Test void exactDuplicateYamlKeysCannotSilentlyOverwriteAClassOrItem() throws Exception {
        for (String source : List.of("""
                classes:
                  scout: {display-name: First}
                  scout: {display-name: Second}
                """, """
                classes:
                  scout:
                    items:
                      '5': {material: POTION}
                      '5': {material: SPLASH_POTION}
                """)) {
            File file = classes(source);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> ClassKit.loadAvailable(file));
            assertTrue(failure.getMessage().contains("classes.yml"));
            assertTrue(failure.getMessage().toLowerCase().contains("duplicate"));
        }
    }

    @Test void malformedMissingAndUnusableClassDocumentsAreActionableConfigurationErrors() throws Exception {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> ClassKit.loadAvailable(directory.resolve("missing-classes.yml").toFile()));
        assertTrue(missing.getMessage().contains("missing-classes.yml"));
        for (String source : List.of("classes: [", "", "other: {}", "classes: []", "classes: {}", "classes: nope", "- scout")) {
            File file = classes(source);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> ClassKit.loadAvailable(file), source);
            assertTrue(failure.getMessage().contains("classes.yml"), failure.getMessage());
        }
    }

    @Test void malformedSectionsDisableTheWholeClassInsteadOfReturningAPartialKit() throws Exception {
        for (String setting : List.of("items: STONE_SWORD", "items: { '5': POTION }", "armor: IRON_HELMET",
                "armor: { boots: IRON_BOOTS }", "offhand: SHIELD", "effects: speed", "effects: { speed: 1 }",
                "armor: { hat: {} }")) {
            ClassKit.LoadResult result = ClassKit.loadAvailable(classes("classes:\n  knight: {}\n  scout:\n    " + setting + "\n"));
            assertEquals(Set.of("knight"), result.kits().keySet(), setting);
            assertEquals(Set.of("scout"), result.errors().keySet(), setting);
            assertTrue(result.errors().get("scout").contains("classes.scout"), setting);
        }
    }

    @Test void explicitYamlNullCannotDropAnItemOrTurnAnInvalidThresholdIntoZero() throws Exception {
        for (String setting : List.of("items: { '5': null }", "items: null", "required-kills: null", "display-name: null",
                "offhand: null", "effects: { speed: { amplifier: null } }")) {
            ClassKit.LoadResult result = ClassKit.loadAvailable(classes("classes:\n  knight: {}\n  scout:\n    " + setting + "\n"));
            assertEquals(Set.of("knight"), result.kits().keySet(), setting);
            assertTrue(result.errors().get("scout").contains("Missing value"), setting);
        }
    }

    @Test void classEffectNumbersCannotSilentlyBecomeDefaultOrInfiniteEffects() throws Exception {
        for (String effect : List.of("{amplifier: true}", "{amplifier: 1.5}", "{amplifier: 11}",
                "{duration-ticks: never}", "{duration-ticks: 0}", "{duration-ticks: 1.5}", "{duration-ticks: -2}")) {
            ClassKit.LoadResult result = ClassKit.loadAvailable(classes("classes:\n  knight: {}\n  scout:\n    effects:\n      speed: " + effect + "\n"));
            assertEquals(Set.of("knight"), result.kits().keySet(), effect);
            assertTrue(result.errors().get("scout").contains("classes.scout.effects.speed"), effect);
        }
    }

    @Test void invalidIdentifiersCannotBeReinterpretedAsNestedOrPersonalClasses() throws Exception {
        ClassKit.LoadResult result = ClassKit.loadAvailable(classes("""
                classes:
                  knight: {}
                  bad.name: {}
                  personal: {}
                  broken: false
                """));
        assertEquals(Set.of("knight"), result.kits().keySet());
        assertEquals(Set.of("bad.name", "personal", "broken"), result.errors().keySet());
    }

    @Test void validLoadPreservesClassMetadataAndReturnsImmutableResults() throws Exception {
        File file = classes("""
                classes:
                  Knight: {display-name: Knight, required-kills: 123}
                """);
        ClassKit.LoadResult result = ClassKit.loadAvailable(file);
        assertTrue(result.errors().isEmpty());
        assertEquals("Knight", result.kits().get("knight").choice().displayName());
        assertEquals(123, result.kits().get("knight").choice().requiredKills());
        assertEquals(Set.of("knight"), ClassKit.load(file).keySet());
        assertThrows(UnsupportedOperationException.class, () -> result.kits().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.errors().put("test", "failure"));
    }

    @Test void missingThresholdKeepsExistingClassesOpen() {
        assertEquals(0, ClassKit.requiredKills(new YamlConfiguration()));
    }

    @Test void acceptsWholeNonnegativeLongThresholdsWithoutTruncation() {
        for (Object value : List.of(0, 100, 500L, Long.MAX_VALUE, BigInteger.valueOf(Long.MAX_VALUE), "1000", "9223372036854775807")) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("required-kills", value);
            assertEquals(Long.parseLong(value.toString()), ClassKit.requiredKills(config));
        }
    }

    @Test void rejectsFractionsOverflowBooleansAndNegativeThresholds() {
        for (Object value : List.of(-1, -1L, 99.9, 100.0, true, false, "-1", "1.5", "1e3", "9223372036854775808",
                new BigInteger("9223372036854775808"), List.of(100))) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("required-kills", value);
            assertThrows(IllegalArgumentException.class, () -> ClassKit.requiredKills(config), "Value: " + value);
        }
    }

    @Test void exactThresholdUnlocksWithoutNarrowingTheLifetimeTotal() {
        ClassChoice archer = new ClassChoice("archer", "Archer", Material.BOW, 100);
        assertFalse(archer.unlocked(99));
        assertTrue(archer.unlocked(100));
        assertTrue(archer.unlocked(Long.MAX_VALUE));
        assertFalse(archer.unlocked(-1));
    }
}
