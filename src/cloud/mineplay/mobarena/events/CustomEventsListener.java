package cloud.mineplay.mobarena.events;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.cevents.ArenaEndEvent;
import cloud.mineplay.mobarena.cevents.ArenaStartEvent;
import cloud.mineplay.mobarena.cevents.ArenaWaveCompleteEvent;

public class CustomEventsListener implements Listener{
	
	@EventHandler
	public void onWaveComplete(ArenaWaveCompleteEvent event) {
	    Arena arena = event.getArena();
	    int completedWave = event.getWave();

	    // Heal & feed players
	    for (Player player : arena.getPlayers()) {
	        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getBaseValue());
	        player.setFoodLevel(20);
	        player.setSaturation(20);
	        player.sendMessage("§aWave " + completedWave + " completed! You have been healed and fed.");
	    }

	    // Increment wave
	    int nextWave = completedWave + 1;
	    arena.setCurrentWave(nextWave);

	    // Delay spawning the next wave
	    Bukkit.getScheduler().runTaskLater(SimpleMobArena.getInstance(), () -> {
	        if (arena.isRunning()) {
	        	SimpleMobArena.getInstance().getArenaListener().spawnWave(arena);
	        }
	    }, 5 * 20L);
	}
	
	@EventHandler
	public void onArenaStart(ArenaStartEvent event) {
	    Arena arena = event.getArena();

	    // Announce start
	    for (Player p : arena.getPlayers()) {
	        p.sendMessage("§aThe arena has started! Get ready!");
	    }

	    // Spawn first wave
	    SimpleMobArena.getInstance().getArenaListener().spawnWave(arena);
	}

	@EventHandler
	public void onArenaEnd(ArenaEndEvent event) {
	    Arena arena = event.getArena();
	    Bukkit.broadcastMessage("§cThe arena '" + arena.getName() + "' has ended.");
	}

}
