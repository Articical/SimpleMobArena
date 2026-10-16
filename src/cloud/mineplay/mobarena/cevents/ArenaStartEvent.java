package cloud.mineplay.mobarena.cevents;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import cloud.mineplay.mobarena.arenas.Arena;

public class ArenaStartEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final Arena arena;

    public ArenaStartEvent(Arena arena) {
        this.arena = arena;
    }

    public Arena getArena() {
        return arena;
    }

    @Override
    public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}
