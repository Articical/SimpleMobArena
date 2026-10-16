package cloud.mineplay.mobarena.arenas;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;

import cloud.mineplay.mobarena.SimpleMobArena;

public class ArenaManager {
	
	private final SimpleMobArena plugin;
	private final Map<String, Arena> arenas = new HashMap<>();
	
	public ArenaManager(SimpleMobArena plugin) {
        this.plugin = plugin;
    }
	
	public void loadArenas() {
        arenas.clear();
        File arenaFolder = new File(plugin.getDataFolder(), "arenas");
        File[] files = arenaFolder.listFiles((dir, name) -> name.endsWith(".yml"));

        if (files == null) {
            plugin.getLogger().warning("No arenas found to load.");
            return;
        }

        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            Arena arena = Arena.loadFromConfig(config, file.getName().replace(".yml", ""));
            arenas.put(arena.getName().toLowerCase(), arena);
            plugin.getLogger().info("Loaded arena: " + arena.getName());
        }
    }
	
	public void saveArenas() {
        for (Arena arena : arenas.values()) {
            saveArena(arena);
        }
    }
	
	public void saveArena(Arena arena) {
        File file = new File(plugin.getDataFolder(), "arenas/" + arena.getName() + ".yml");
        YamlConfiguration config = arena.serializeToConfig();
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save arena " + arena.getName() + ": " + e.getMessage());
        }
    }
	
	public void addArena(Arena arena) {
        arenas.put(arena.getName().toLowerCase(), arena);
        saveArena(arena);
    }
	
	public void removeArena(String name) {
        arenas.remove(name.toLowerCase());
        File file = new File(plugin.getDataFolder(), "arenas/" + name + ".yml");
        if (file.exists()) {
            file.delete();
        }
    }
	
	public Arena getArena(String name) {
        return arenas.get(name.toLowerCase());
    }
	
	public Collection<Arena> getArenas() {
        return arenas.values();
    }
	
	public List<String> listArenas() {
        List<String> names = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            names.add(arena.getName());
        }
        return names;
    }
	
	public void reloadArenas() {
        loadArenas();
        plugin.getLogger().info("Arenas reloaded.");
    }
	
	public void stopAllArenas() {
	    for (Arena arena : arenas.values()) {
	        if (arena.isRunning()) {
	            arena.endArena();
	        }
	    }
	}

}
