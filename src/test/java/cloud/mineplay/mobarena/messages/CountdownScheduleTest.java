package cloud.mineplay.mobarena.messages;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CountdownScheduleTest {
    @Test void defaultsAnnounceEachThresholdOnceAndNeverAnnounceZero() {
        CountdownSchedule schedule = new CountdownSchedule(new YamlConfiguration());
        schedule.reset(15);
        assertFalse(schedule.advance(15), "The caller has already announced the initial countdown.");
        assertFalse(schedule.advance(14));
        assertTrue(schedule.advance(10));
        assertFalse(schedule.advance(10));
        assertFalse(schedule.advance(9));
        assertTrue(schedule.advance(5));
        assertFalse(schedule.advance(4));
        assertTrue(schedule.advance(3));
        assertTrue(schedule.advance(2));
        assertTrue(schedule.advance(1));
        assertFalse(schedule.advance(0));
        assertFalse(schedule.advance(-1));
    }

    @Test void crossingSeveralThresholdsCollapsesToOneNoticeAtTheCurrentTime() {
        CountdownSchedule schedule = new CountdownSchedule(new YamlConfiguration());
        schedule.reset(30);
        assertTrue(schedule.advance(4));
        assertFalse(schedule.advance(4));
        assertTrue(schedule.advance(1));
        assertFalse(schedule.advance(1));
    }

    @Test void explicitEmptyListDisablesFollowUpAnnouncements() {
        CountdownSchedule schedule = configured(List.of());
        schedule.reset(30);
        for (int remaining = 29; remaining >= 0; remaining--) assertFalse(schedule.advance(remaining));
    }

    @Test void unsortedDuplicateThresholdsDoNotProduceDuplicateAnnouncements() {
        CountdownSchedule schedule = configured(List.of(5, 10, 5, 10));
        schedule.reset(15);
        assertTrue(schedule.advance(10));
        assertFalse(schedule.advance(10));
        assertFalse(schedule.advance(9));
        assertTrue(schedule.advance(5));
        assertFalse(schedule.advance(5));
        assertFalse(schedule.advance(1));
    }

    @Test void resetAllowsAFullNewCountdownAfterCancellation() {
        CountdownSchedule schedule = new CountdownSchedule(new YamlConfiguration());
        schedule.reset(15);
        assertTrue(schedule.advance(5));
        schedule.reset(0);
        assertFalse(schedule.advance(0));
        schedule.reset(15);
        assertFalse(schedule.advance(15));
        assertTrue(schedule.advance(10));
    }

    @Test void invalidTypesAndOutOfRangeThresholdsAreRejected() {
        for (Object value : List.of("15,10,5", 15, true, List.of(0), List.of(-1), List.of(601),
            List.of("5"), List.of(5.0), List.of(5L))) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("announcements.countdown-seconds", value);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new CountdownSchedule(config));
            assertTrue(failure.getMessage().contains("announcements.countdown-seconds"));
        }
    }

    @Test void configuredBoundaryValuesAreAccepted() {
        assertDoesNotThrow(() -> configured(List.of(1, 600)));
    }

    private static CountdownSchedule configured(List<?> values) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("announcements.countdown-seconds", values);
        return new CountdownSchedule(config);
    }
}
