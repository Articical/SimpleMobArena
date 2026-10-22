package cloud.mineplay.mobarena.duels;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Team slots include equipment reservations; eliminated players keep their recorded team. */
public final class DuelTeams {
    public enum Team { RED, BLUE;
        public String label() { return this == RED ? "Red" : "Blue"; }
    }
    private final Map<UUID, Team> assignments = new HashMap<>();

    public Team reserve(UUID player, String requested, boolean choose, int size) {
        if (assignments.containsKey(player)) throw new IllegalStateException("You already have a team slot.");
        Team team;
        if (choose) {
            try { team = Team.valueOf(requested == null ? "" : requested.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("Choose the red or blue team."); }
        } else {
            if (requested != null && !requested.isBlank()) throw new IllegalArgumentException("This arena assigns teams automatically.");
            team = count(Team.RED) <= count(Team.BLUE) ? Team.RED : Team.BLUE;
        }
        if (count(team) >= size) throw new IllegalStateException(team.label() + " team is full.");
        assignments.put(player, team);
        return team;
    }
    public Team team(UUID player) { return assignments.get(player); }
    public int count(Team team) { return (int) assignments.values().stream().filter(team::equals).count(); }
    public int alive(Team team, Set<UUID> active) { return (int) active.stream().filter(id -> team == assignments.get(id)).count(); }
    public boolean opponents(UUID a, UUID b) { return assignments.get(a) != null && assignments.get(b) != null && assignments.get(a) != assignments.get(b); }
    public void remove(UUID player) { assignments.remove(player); }
    public void clear() { assignments.clear(); }
}
