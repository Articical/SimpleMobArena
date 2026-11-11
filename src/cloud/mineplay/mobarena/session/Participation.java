package cloud.mineplay.mobarena.session;

import java.time.Instant;
import java.util.UUID;

public final class Participation {
    public final UUID playerId;
    public final String playerName;
    public int kills;
    public double damage;
    private final java.util.Map<String,Integer> mobKills = new java.util.TreeMap<>();
    private Instant started;
    private Instant left;
    public Participation(UUID id) { this(id,id.toString()); }
    public Participation(UUID id,String name) { playerId = id; playerName = name; }
    public void recordMobKill(String type) {
        if (type == null || !type.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid mob type");
        mobKills.merge(type,1,Math::addExact);
        kills = Math.incrementExact(kills);
    }
    public java.util.Map<String,Integer> mobKills() { return java.util.Map.copyOf(mobKills); }
    public void start(Instant at) { started = at; }
    public void leave(Instant at) { if (left == null) left = at; }
    public long seconds(Instant finish) {
        return started == null ? 0 : Math.max(0, java.time.Duration.between(started, left == null ? finish : left).getSeconds());
    }
}
