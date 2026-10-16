package cloud.mineplay.mobarena.cevents;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import cloud.mineplay.mobarena.arenas.Arena;

public class ArenaWaveCompleteEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final Arena arena;
    private final int wave;

    public ArenaWaveCompleteEvent(Arena arena, int wave) {
        this.arena = arena;
        this.wave = wave;
    }

    public Arena getArena() { return arena; }
    public int getWave() { return wave; }

    @Override
    public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}
