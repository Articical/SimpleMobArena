package cloud.mineplay.mobarena.session;

import cloud.mineplay.mobarena.enums.GameState;
import java.util.*;

/** Pure state machine: the only authority for transitions, elimination and wave ownership. */
public final class SessionState {
    private GameState state = GameState.WAITING;
    private final Set<UUID> active = new LinkedHashSet<>();
    private final Set<UUID> mobs = new HashSet<>();
    private int pendingSpawns;

    public GameState state() { return state; }
    public Set<UUID> active() { return Set.copyOf(active); }
    public Set<UUID> mobs() { return Set.copyOf(mobs); }
    public int pendingSpawns() { return pendingSpawns; }
    public boolean join(UUID player) {
        if (state != GameState.WAITING && state != GameState.COUNTDOWN) return false;
        return active.add(player);
    }
    public boolean eliminate(UUID player) { return active.remove(player); }
    public void transition(GameState next) {
        boolean allowed = switch (state) {
            case WAITING -> next == GameState.COUNTDOWN || next == GameState.ENDING;
            case COUNTDOWN -> next == GameState.WAITING || next == GameState.ACTIVE || next == GameState.ENDING;
            case ACTIVE -> next == GameState.INTERMISSION || next == GameState.ENDING;
            case INTERMISSION -> next == GameState.ACTIVE || next == GameState.ENDING;
            case ENDING -> next == GameState.FINISHED;
            case FINISHED -> next == GameState.WAITING;
        };
        if (!allowed) throw new IllegalStateException("Invalid arena transition: " + state + " -> " + next);
        if ((next == GameState.COUNTDOWN || next == GameState.ACTIVE) && active.isEmpty())
            throw new IllegalStateException("Cannot start an arena without participants.");
        state = next;
    }
    public void beginWave(int count) {
        if (state != GameState.ACTIVE || count <= 0 || pendingSpawns != 0 || !mobs.isEmpty())
            throw new IllegalStateException("Wave cannot start with outstanding mobs or spawns.");
        pendingSpawns = count;
    }
    public void spawned(UUID id) {
        if (state != GameState.ACTIVE || pendingSpawns <= 0 || !mobs.add(id))
            throw new IllegalStateException("Unexpected or duplicate mob spawn.");
        pendingSpawns--;
    }
    public boolean died(UUID id) { return mobs.remove(id); }
    public boolean waveComplete() { return state == GameState.ACTIVE && pendingSpawns == 0 && mobs.isEmpty(); }
    public void clearMobs() { pendingSpawns = 0; mobs.clear(); }
}
