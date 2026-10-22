package cloud.mineplay.mobarena.arenas;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import org.bukkit.Chunk;
import org.bukkit.plugin.Plugin;

/** Paper tickets are per plugin, so neighbouring arenas must share a reference count. Server-thread only. */
final class ArenaChunkTickets {
    private static final Map<Plugin, Map<Chunk, Integer>> owners = new WeakHashMap<>();
    private ArenaChunkTickets() { }
    static void acquire(Plugin plugin, Chunk chunk) {
        Map<Chunk,Integer> counts = owners.computeIfAbsent(plugin, ignored -> new HashMap<>());
        int count = counts.getOrDefault(chunk,0);
        if (count == 0) chunk.addPluginChunkTicket(plugin);
        counts.put(chunk,count+1);
    }
    static void release(Plugin plugin, Chunk chunk) {
        Map<Chunk,Integer> counts = owners.get(plugin);
        if (counts == null) return;
        Integer count = counts.get(chunk);
        if (count == null) return;
        if (count > 1) counts.put(chunk,count-1);
        else { chunk.removePluginChunkTicket(plugin); counts.remove(chunk); }
        if (counts.isEmpty()) owners.remove(plugin);
    }
}
