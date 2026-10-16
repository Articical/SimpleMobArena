package cloud.mineplay.mobarena;

import java.io.File;

import org.bukkit.plugin.java.JavaPlugin;

import cloud.mineplay.mobarena.arenas.ArenaManager;
import cloud.mineplay.mobarena.commands.CommandManager;
import cloud.mineplay.mobarena.events.ArenaListener;

public class SimpleMobArena extends JavaPlugin {

    private static SimpleMobArena instance;
    private ArenaManager arenaManager;
    private ArenaListener arenaListener;
    
    @Override
    public void onEnable() {
        instance = this;
        
        setArenaListener(new ArenaListener(this));

        // Create folders
        createDirectory("arenas");
        createDirectory("playerdata");

        // Save default config if you have one
        saveDefaultConfig();

        // Initialize ArenaManager and load arenas
        arenaManager = new ArenaManager(this);
        arenaManager.loadArenas();

        // Register /mobarena commands
        getCommand("mobarena").setExecutor(new CommandManager(this));

        // Register event listeners
        getServer().getPluginManager().registerEvents(new ArenaListener(this), this);

        getLogger().info("SimpleMobArena has been enabled!");
    }

    @Override
    public void onDisable() {
        // Save arenas to disk
        if (arenaManager != null) {
            arenaManager.saveArenas();
        }

        getLogger().info("SimpleMobArena has been disabled.");
    }

    public void createDirectory(String name) {
        File dir = new File(getDataFolder(), name);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static SimpleMobArena getInstance() {
        return instance;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

	/**
	 * @return the arenaListener
	 */
	public ArenaListener getArenaListener() {
		return arenaListener;
	}

	/**
	 * @param arenaListener the arenaListener to set
	 */
	public void setArenaListener(ArenaListener arenaListener) {
		this.arenaListener = arenaListener;
	}
}
