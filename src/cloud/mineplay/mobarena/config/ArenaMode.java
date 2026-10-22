package cloud.mineplay.mobarena.config;

/** The activity configured for one arena. */
public enum ArenaMode {
    MOB_ARENA("Mob arena"),
    DUEL("Duel"),
    TEAM_DUEL("Team duel");

    private final String label;

    ArenaMode(String label) { this.label = label; }

    public boolean isDuel() { return this != MOB_ARENA; }
    public String label() { return label; }
}
