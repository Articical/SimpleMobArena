package cloud.mineplay.mobarena.mobs;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class RapidFireSettingsTest {
    @Test void absentSectionDisablesTheVariantAndAnEmptySectionGetsBoundedDefaults() {
        YamlConfiguration settings = new YamlConfiguration();
        assertNull(RapidFireSettings.read(settings));
        settings.createSection("rapid-fire");
        assertEquals(new RapidFireSettings(.02, 8, 8), RapidFireSettings.read(settings));
    }

    @ParameterizedTest
    @CsvSource({"chance,-0.01", "chance,1.01", "chance,NaN", "chance,Infinity",
            "minimum-wave,2", "minimum-wave,10001", "minimum-wave,8.5",
            "interval-ticks,3", "interval-ticks,101", "interval-ticks,4.5"})
    void invalidNumbersAreRejected(String key, String rawValue) {
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("rapid-fire." + key, Double.valueOf(rawValue));
        assertThrows(IllegalArgumentException.class, () -> RapidFireSettings.read(settings));
    }

    @Test void malformedSectionsAndNumericStringsAreNotSilentlyIgnored() {
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("rapid-fire", true);
        assertThrows(IllegalArgumentException.class, () -> RapidFireSettings.read(settings));
        settings.set("rapid-fire", null);
        settings.set("rapid-fire.chance", "0.02");
        assertThrows(IllegalArgumentException.class, () -> RapidFireSettings.read(settings));
    }

    @Test void arenaMobValidationReportsWrongTypesAndInvalidSettings() {
        YamlConfiguration mobs = new YamlConfiguration();
        mobs.set("STRAY.rapid-fire.chance", .02);
        mobs.set("SKELETON.rapid-fire.interval-ticks", 0);
        var errors = MobSpawn.validate(mobs);
        assertTrue(errors.stream().anyMatch(error -> error.contains("STRAY.rapid-fire is only supported")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("SKELETON.rapid-fire.interval-ticks")));
    }

    @Test void fastestAndSlowestAllowedIntervalsAreAccepted() {
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("rapid-fire.interval-ticks", 4);
        settings.set("rapid-fire.minimum-wave", 3);
        assertEquals(4, RapidFireSettings.read(settings).intervalTicks());
        settings.set("rapid-fire.interval-ticks", 100);
        assertEquals(100, RapidFireSettings.read(settings).intervalTicks());
    }
}
