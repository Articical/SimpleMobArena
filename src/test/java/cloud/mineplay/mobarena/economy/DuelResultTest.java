package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.duels.DuelResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DuelResultTest {
    @Test void rejectsUnknownModesWinnersTeamsAndDuplicateParticipants() {
        var sample = DuelStoreTest.result(UUID.randomUUID(), DuelStoreTest.START.plusSeconds(120));
        assertThrows(IllegalArgumentException.class, () -> new DuelResult(sample.sessionId(), sample.arenaId(), "MOBS", sample.startedAt(),
                sample.finishedAt(), "RED", false, "DONE", sample.participants()));
        assertThrows(IllegalArgumentException.class, () -> new DuelResult(sample.sessionId(), sample.arenaId(), "DUEL", sample.startedAt(),
                sample.finishedAt(), "GREEN", false, "DONE", sample.participants()));
        assertThrows(IllegalArgumentException.class, () -> new DuelResult.Participant(UUID.randomUUID(), "Alex", "GREEN", 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new DuelResult(sample.sessionId(), sample.arenaId(), "DUEL", sample.startedAt(),
                sample.finishedAt(), "RED", false, "DONE", List.of(sample.participants().getFirst(), sample.participants().getFirst())));
    }

    @Test void rejectsNegativeNonfiniteAndBackwardsTimeValues() {
        for (double damage : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new DuelResult.Participant(UUID.randomUUID(), "Alex", "RED", 0, damage, 0, false));
        }
        assertThrows(IllegalArgumentException.class, () -> new DuelResult.Participant(UUID.randomUUID(), "Alex", "RED", -1, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new DuelResult.Participant(UUID.randomUUID(), "Alex", "RED", 0, 0, -1, false));
        assertThrows(IllegalArgumentException.class, () -> new DuelResult(UUID.randomUUID(), "arena", "DUEL", DuelStoreTest.START,
                DuelStoreTest.START.minusSeconds(1), "DRAW", true, "CANCELLED", List.of()));
    }

    @Test void detachesMutableRosterAndRetainsBoundedBedrockNames() {
        var player = new DuelResult.Participant(UUID.randomUUID(), ".Player 玩家", "RED", 0, -0.0, 0, false);
        var mutable = new ArrayList<>(List.of(player));
        var result = new DuelResult(UUID.randomUUID(), "arena", "DUEL", DuelStoreTest.START, DuelStoreTest.START,
                "DRAW", true, "CANCELLED", mutable);
        mutable.clear();
        assertEquals(List.of(player), result.participants());
        assertThrows(UnsupportedOperationException.class, () -> result.participants().clear());
        assertEquals(0L, Double.doubleToRawLongBits(player.damage()));
        assertThrows(IllegalArgumentException.class, () -> new DuelResult.Participant(UUID.randomUUID(), "x".repeat(65), "RED", 0, 0, 0, false));
    }
}
