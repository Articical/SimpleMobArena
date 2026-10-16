package cloud.mineplay.mobarena.session;

import cloud.mineplay.mobarena.enums.GameState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionStateTest {
    @Test void emptySessionCannotStart() {
        SessionState state=new SessionState();
        assertThrows(IllegalStateException.class,()->state.transition(GameState.COUNTDOWN));
        assertEquals(GameState.WAITING,state.state());
    }
    @Test void centralTransitionsRejectSkippingCountdownAndDoubleStart() {
        SessionState state=new SessionState(); state.join(UUID.randomUUID());
        assertThrows(IllegalStateException.class,()->state.transition(GameState.ACTIVE));
        state.transition(GameState.COUNTDOWN); state.transition(GameState.ACTIVE);
        assertThrows(IllegalStateException.class,()->state.transition(GameState.ACTIVE));
    }
    @Test void onlyWaitingAndCountdownAcceptPlayers() {
        SessionState state=active();
        assertFalse(state.join(UUID.randomUUID()));
        state.transition(GameState.INTERMISSION); assertFalse(state.join(UUID.randomUUID()));
        state.transition(GameState.ENDING); assertFalse(state.join(UUID.randomUUID()));
        state.transition(GameState.FINISHED); assertFalse(state.join(UUID.randomUUID()));
        state.transition(GameState.WAITING); assertTrue(state.join(UUID.randomUUID()));
    }
    @Test void duplicateEliminationAndReconnectCannotReenterMatch() {
        SessionState state=new SessionState(); UUID id=UUID.randomUUID(); state.join(id);
        state.transition(GameState.COUNTDOWN); state.transition(GameState.ACTIVE);
        assertTrue(state.eliminate(id)); assertFalse(state.eliminate(id)); assertFalse(state.join(id));
        assertTrue(state.active().isEmpty());
    }
    @Test void partialSpawnBatchIsNotACompletedWave() {
        SessionState state=active(); state.beginWave(3); UUID first=UUID.randomUUID();
        state.spawned(first); assertTrue(state.died(first)); assertFalse(state.waveComplete());
        UUID second=UUID.randomUUID(),third=UUID.randomUUID(); state.spawned(second); state.spawned(third);
        assertTrue(state.died(second)); assertFalse(state.waveComplete());
        assertTrue(state.died(third)); assertTrue(state.waveComplete());
    }
    @Test void killCreditOnlyOnceForRegisteredMobs() {
        SessionState state=active(); state.beginWave(1); UUID mob=UUID.randomUUID();
        assertFalse(state.died(mob)); state.spawned(mob); assertTrue(state.died(mob)); assertFalse(state.died(mob));
    }
    @Test void duplicateWaveCannotOverwriteOutstandingSpawnCount() {
        SessionState state=active(); state.beginWave(4);
        assertThrows(IllegalStateException.class,()->state.beginWave(4)); assertEquals(4,state.pendingSpawns());
    }
    @Test void duplicateMobIdCannotConsumeAnotherSpawn() {
        SessionState state=active(); state.beginWave(2); UUID mob=UUID.randomUUID(); state.spawned(mob);
        assertThrows(IllegalStateException.class,()->state.spawned(mob)); assertEquals(1,state.pendingSpawns());
    }
    @Test void noWaveCompletionOutsideActiveState() {
        SessionState state=active(); state.beginWave(1); UUID mob=UUID.randomUUID(); state.spawned(mob);
        state.transition(GameState.ENDING); state.clearMobs(); assertFalse(state.waveComplete());
    }
    @Test void countdownCanReturnToWaitingWhenPlayersLeave() {
        SessionState state=new SessionState(); UUID id=UUID.randomUUID(); state.join(id); state.transition(GameState.COUNTDOWN);
        state.eliminate(id); state.transition(GameState.WAITING); assertTrue(state.join(id));
    }
    @Test void disconnectStopsParticipationTimeAndDuplicateLeaveCannotExtendIt() {
        Participation player=new Participation(UUID.randomUUID()); Instant start=Instant.parse("2026-10-17T00:00:00Z");
        player.start(start); player.leave(start.plusSeconds(70)); player.leave(start.plusSeconds(300));
        assertEquals(70,player.seconds(start.plusSeconds(500)));
    }
    @Test void waitingTimeDoesNotCountForRewardEligibility() {
        Participation player=new Participation(UUID.randomUUID()); assertEquals(0,player.seconds(Instant.now()));
    }
    private static SessionState active() {
        SessionState state=new SessionState(); state.join(UUID.randomUUID()); state.transition(GameState.COUNTDOWN); state.transition(GameState.ACTIVE); return state;
    }
}
