package cloud.mineplay.mobarena.duels;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cloud.mineplay.mobarena.duels.DuelTeams.Team.*;

class DuelTeamsTest {
    @Test void automaticTeamsBalanceReservationsAndReuseCancelledSlot() {
        DuelTeams teams = new DuelTeams();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertEquals(RED, teams.reserve(first, null, false, 2));
        assertEquals(BLUE, teams.reserve(second, null, false, 2));
        assertEquals(RED, teams.reserve(UUID.randomUUID(), null, false, 2));
        teams.remove(second);
        assertEquals(BLUE, teams.reserve(UUID.randomUUID(), null, false, 2));
        assertThrows(IllegalArgumentException.class, () -> teams.reserve(UUID.randomUUID(), "red", false, 2));
    }
    @Test void chosenTeamsCannotExceedCapacityAndHistorySurvivesElimination() {
        DuelTeams teams = new DuelTeams();
        UUID red = UUID.randomUUID(), blue = UUID.randomUUID();
        teams.reserve(red, "red", true, 1);
        assertThrows(IllegalStateException.class, () -> teams.reserve(UUID.randomUUID(), "RED", true, 1));
        assertThrows(IllegalArgumentException.class, () -> teams.reserve(UUID.randomUUID(), null, true, 1));
        teams.reserve(blue, "blue", true, 1);
        assertTrue(teams.opponents(red, blue));
        assertFalse(teams.opponents(red, red));
        assertEquals(0, teams.alive(RED, Set.of(blue)));
        assertEquals(RED, teams.team(red));
        teams.clear();
        assertNull(teams.team(red));
    }
}
