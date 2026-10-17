package cloud.mineplay.mobarena.equipment;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClassKitTest {
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
