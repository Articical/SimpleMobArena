package cloud.mineplay.mobarena.arenas;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.cevents.ArenaEndEvent;
import cloud.mineplay.mobarena.cevents.ArenaStartEvent;
import cloud.mineplay.mobarena.enums.GameState;
import cloud.mineplay.mobarena.enums.JoinResult;
import cloud.mineplay.mobarena.enums.RemoveResult;

public class Arena {
	
	/*
	 * TODO: Wave Logic / Mob Spawn logic / File saving logic.
	 */
	
	private final String name;
	private final List<Location> spawnLocations;
	private final String difficulty;
	private final int maxPlayers;
	
	private final Set<Player> players;
	
	private final Set<UUID> activeMobs = new HashSet<>();
	
	private final List<EntityType> mobTypes;
	
	private GameState arenaState;
	
	private int currentWave = 0;
	private boolean running = false;
	
	public Arena(String name, int maxPlayers, List<Location> spawnLocations, String difficulty, List<EntityType> mobTypes) {
		this.name = name;
		this.spawnLocations = spawnLocations;
		this.difficulty = difficulty;
		this.maxPlayers = maxPlayers;
		this.players = new HashSet<>();
		this.arenaState = GameState.EMPTY;
		this.mobTypes = mobTypes;
	}

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @return the spawnLocations
	 */
	public List<Location> getSpawnLocations() {
		return spawnLocations;
	}

	/**
	 * @return the difficulty
	 */
	public String getDifficulty() {
		return difficulty;
	}

	/**
	 * @return the players
	 */
	public Set<Player> getPlayers() {
		return players;
	}
	
	/**
	 * @return the maxPlayers
	 */
	public int getMaxPlayers() {
		return maxPlayers;
	}
	
	/**
	 * Adds a player to this arena.
	 */
	public JoinResult addPlayer(Player player) {
	    if (running) return JoinResult.ALREADY_RUNNING;
	    if (players.contains(player)) return JoinResult.ALREADY_IN_ARENA;
	    if (players.size() >= 4) return JoinResult.ARENA_FULL;

	    players.add(player);

	    // Teleport to spawn if defined
	    if (!spawnLocations.isEmpty()) {
	        player.teleport(spawnLocations.get(0));
	    }

	    // Starter kit
	    player.getInventory().clear();

	    player.getInventory().addItem(new ItemStack(Material.STONE_SWORD));
	    player.getInventory().addItem(new ItemStack(Material.BOW));
	    player.getInventory().addItem(new ItemStack(Material.ARROW, 34));

	    player.getInventory().setHelmet(new ItemStack(Material.LEATHER_HELMET));
	    player.getInventory().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
	    player.getInventory().setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
	    player.getInventory().setBoots(new ItemStack(Material.LEATHER_BOOTS));

	    player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getBaseValue());
	    player.setFoodLevel(20);
	    player.setSaturation(20);

	    player.sendMessage("§aYou have received your starter kit!");

	    return JoinResult.SUCCESS;
	}
	
	/**
	 * Removes a player from this arena.
	 */
	
	public RemoveResult removePlayer(Player player) {
		if (!players.contains(player)) {
	        return RemoveResult.NOT_IN_ARENA;
	    }

	    players.remove(player);

	    // Clear inventory
	    player.getInventory().clear();

	    // Teleport to world spawn
	    player.teleport(player.getWorld().getSpawnLocation());

	    // Heal and feed
	    player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getBaseValue());
	    player.setFoodLevel(20);
	    player.setSaturation(20);

	    player.sendMessage("§cYou have left the arena and your kit has been removed.");
	    
	    if(players.isEmpty() && running) {
	    	setRunning(false);
	    	removeAllMobs();
	    }

	    if (players.isEmpty()) {
	        return RemoveResult.ARENA_NOW_EMPTY;
	    } else {
	        return RemoveResult.SUCCESS;
	    }
	}
	
	/**
	 * Whether the arena has any players.
	 */
	public boolean isActive() {
		return !players.isEmpty();
	}
	
	/**
	 * Serialize this arena to a YamlConfiguration for saving.
	 */
	public YamlConfiguration serializeToConfig() {
		YamlConfiguration config = new YamlConfiguration();
		config.set("difficulty", this.difficulty);
		config.set("maxPlayers", this.maxPlayers);
		
		List<String> serializedSpawns = new ArrayList<>();
		for(Location loc : spawnLocations) {
			serializedSpawns.add(serializeLocation(loc));
		}
		config.set("spawns", serializedSpawns);
		
		List<String> mobNames = new ArrayList<>();
	    for (EntityType type : mobTypes) {
	        mobNames.add(type.name());
	    }
	    config.set("mobTypes", mobNames);
		
		return config;
	}
	
	/**
	 * Load an Arena from a YamlConfiguration.
	 */
	public static Arena loadFromConfig(YamlConfiguration config, String name) {
		String difficulty = config.getString("difficulty", "NORMAL");
		int maxPlayers = config.getInt("maxPlayers", 3);
		List<String> spawnStrings = config.getStringList("spawns");
		List<Location> spawns = new ArrayList<>();
		for(String s : spawnStrings) {
			Location loc = deserializeLocation(s);
			if(loc != null) {
				spawns.add(loc);
			}
		}
		
		List<String> mobNames = config.getStringList("mobTypes");
		List<EntityType> mobTypes = new ArrayList<>();
		for (String mobName : mobNames) {
		    try {
		        mobTypes.add(EntityType.valueOf(mobName));
		    } catch (IllegalArgumentException e) {
		        Bukkit.getLogger().warning("Invalid mob type '" + mobName + "' in arena '" + name + "'");
		    }
		}
		
		return new Arena(name, maxPlayers, spawns, difficulty, mobTypes);
	}
	
	/**
	 * Utility: Convert a Location to a string.
	 */
	private static String serializeLocation(Location loc) {
        return loc.getWorld().getName() + ","
                + loc.getX() + ","
                + loc.getY() + ","
                + loc.getZ() + ","
                + loc.getYaw() + ","
                + loc.getPitch();
    }
	
	/**
     * Utility: Convert a string to a Location.
     */
    private static Location deserializeLocation(String s) {
        try {
            String[] parts = s.split(",");
            World world = Bukkit.getWorld(parts[0]);
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = Float.parseFloat(parts[4]);
            float pitch = Float.parseFloat(parts[5]);
            return new Location(world, x, y, z, yaw, pitch);
        } catch (Exception e) {
            Bukkit.getLogger().warning("Failed to deserialize location: " + s);
            return null;
        }
    }

	/**
	 * @return the currentWave
	 */
	public int getCurrentWave() {
		return currentWave;
	}

	/**
	 * @param currentWave the currentWave to set
	 */
	public void setCurrentWave(int currentWave) {
		this.currentWave = currentWave;
	}

	/**
	 * @return the running
	 */
	public boolean isRunning() {
		return running;
	}

	/**
	 * @param running the running to set
	 */
	public void setRunning(boolean running) {
		this.running = running;
	}

	/**
	 * @return the activeMobs
	 */
	public Set<UUID> getActiveMobs() {
		return activeMobs;
	}

	/**
	 * @return the arenaState
	 */
	public GameState getArenaState() {
		return arenaState;
	}
	
	public List<EntityType> getMobTypes(){
		return this.mobTypes;
	}
	
	public void removeAllMobs() {
	    for (UUID id : new HashSet<>(activeMobs)) {
	        Entity entity = Bukkit.getEntity(id);
	        if (entity != null && !entity.isDead()) {
	            entity.remove();
	        }
	    }
	    activeMobs.clear();
	}
	
	public void startArena() {
	    if (running) return;
	    running = true;
	    currentWave = 1;
	    SimpleMobArena.getInstance().getArenaListener().spawnWave(this);
	    Bukkit.getPluginManager().callEvent(new ArenaStartEvent(this));
	}

	public void endArena() {
	    if (!running) return;
	    running = false;
	    currentWave = 0;
	    removeAllMobs();
	    for (Player p : new ArrayList<>(players)) {
	        removePlayer(p);
	    }
	    Bukkit.getPluginManager().callEvent(new ArenaEndEvent(this));
	}

}
