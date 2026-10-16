package cloud.mineplay.mobarena.session;

import java.time.Instant;
import java.util.UUID;

public final class Participation {
    public final UUID playerId;
    public final String playerName;
    public int kills;
    public double damage;
    private Instant started;
    private Instant left;
    public Participation(UUID id) { this(id,id.toString()); }
    public Participation(UUID id,String name) { playerId = id; playerName = name; }
    public void start(Instant at) { started = at; }
    public void leave(Instant at) { if (left == null) left = at; }
    public long seconds(Instant finish) {
        return started == null ? 0 : Math.max(0, java.time.Duration.between(started, left == null ? finish : left).getSeconds());
    }
}
