package cloud.mineplay.mobarena.commands;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;

public class CommandManager implements CommandExecutor{
	
	private final SimpleMobArena plugin;
	
	private static final List<EntityType> DEFAULT_MOB_TYPES = List.of(
		    EntityType.ZOMBIE,
		    EntityType.SKELETON,
		    EntityType.SPIDER,
		    EntityType.WOLF,
		    EntityType.IRON_GOLEM,
		    EntityType.CREEPER
		);
	
	public CommandManager(SimpleMobArena plugin) {
		this.plugin = plugin;
	}
	
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		
		if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
		
		String sub = args[0].toLowerCase();
		
		switch(sub) {
		
		case "create":
			handleCreate(sender, args);
			break;
			
		case "setspawn":
			handleSetSpawn(sender, args);
			break;
		case "start":
			handleStart(sender, args);
			break;
		case "stop":
			handleStop(sender, args);
			break;
		case "forcewave":
			handleForceWave(sender, args);
			break;
		case "list":
			handleList(sender);
			break;
		
		default:
			sender.sendMessage("§cUnknown subcommand. Use /mobarena for help.");
			break;
		}
		
		return true;
	}
	
	private void sendHelp(CommandSender sender) {
	    sender.sendMessage("§eMobArena Commands:");

	    boolean any = false;

	    if (sender.hasPermission("mobarena.admin.create")) {
	        sender.sendMessage("§7/mobarena create <name> <maxPlayers>");
	        any = true;
	    }
	    if (sender.hasPermission("mobarena.admin.setspawn")) {
	        sender.sendMessage("§7/mobarena setspawn <arena>");
	        any = true;
	    }
	    if (sender.hasPermission("mobarena.admin.start")) {
	        sender.sendMessage("§7/mobarena start <arena>");
	        any = true;
	    }
	    if (sender.hasPermission("mobarena.admin.stop")) {
	        sender.sendMessage("§7/mobarena stop <arena>");
	        any = true;
	    }
	    if (sender.hasPermission("mobarena.admin.forcewave")) {
	        sender.sendMessage("§7/mobarena forcewave <arena>");
	        any = true;
	    }
	    if (sender.hasPermission("mobarena.admin.list")) {
	        sender.sendMessage("§7/mobarena list");
	        any = true;
	    }

	    if (!any) {
	        sender.sendMessage("§7No commands available.");
	    }
	}
	
	private void handleCreate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mobarena.admin")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /mobarena create <name> <maxPlayers>");
            return;
        }

        String name = args[1];

        if (plugin.getArenaManager().getArena(name) != null) {
            sender.sendMessage("§cAn arena with that name already exists.");
            return;
        }

        int maxPlayers;
        try {
            maxPlayers = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid number for maxPlayers.");
            return;
        }

        Arena arena = new Arena(name, maxPlayers, new ArrayList<>(), "NORMAL", DEFAULT_MOB_TYPES);
        plugin.getArenaManager().addArena(arena);

        sender.sendMessage("§aArena '" + name + "' created. Use /mobarena setspawn " + name + " to set spawn points.");
    }
	
	private void handleSetSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can set spawns.");
            return;
        }

        if (!sender.hasPermission("mobarena.admin")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /mobarena setspawn <arena>");
            return;
        }

        String name = args[1];
        Arena arena = plugin.getArenaManager().getArena(name);

        if (arena == null) {
            sender.sendMessage("§cNo arena with that name exists.");
            return;
        }

        Player player = (Player) sender;
        Location loc = player.getLocation();
        arena.getSpawnLocations().add(loc);
        plugin.getArenaManager().saveArena(arena);

        sender.sendMessage("§aAdded spawn point #" + arena.getSpawnLocations().size() + " to arena '" + name + "'.");
    }
	
	private void handleStart(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mobarena.admin")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /mobarena start <arena>");
            return;
        }

        String name = args[1];
        Arena arena = plugin.getArenaManager().getArena(name);

        if (arena == null) {
            sender.sendMessage("§cNo arena with that name exists.");
            return;
        }

        if (arena.isRunning()) {
            sender.sendMessage("§cThat arena is already running.");
            return;
        }

        arena.startArena();
        sender.sendMessage("§aArena '" + name + "' has started.");

    }
	
	private void handleStop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mobarena.admin")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /mobarena stop <arena>");
            return;
        }

        String name = args[1];
        Arena arena = plugin.getArenaManager().getArena(name);

        if (arena == null) {
            sender.sendMessage("§cNo arena with that name exists.");
            return;
        }

        if (!arena.isRunning()) {
            sender.sendMessage("§cThat arena is not running.");
            return;
        }

        arena.endArena();

        sender.sendMessage("§cArena '" + name + "' has been stopped.");
    }
	
	private void handleForceWave(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mobarena.admin")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /mobarena forcewave <arena>");
            return;
        }

        String name = args[1];
        Arena arena = plugin.getArenaManager().getArena(name);

        if (arena == null) {
            sender.sendMessage("§cNo arena with that name exists.");
            return;
        }

        if (!arena.isRunning()) {
            sender.sendMessage("§cThat arena is not running.");
            return;
        }

        arena.setCurrentWave(arena.getCurrentWave() + 1);
        plugin.getArenaListener().spawnWave(arena);
        sender.sendMessage("§aForced next wave in arena '" + name + "'.");
    }
	
	private void handleList(CommandSender sender) {
        List<Arena> arenas = new ArrayList<>(plugin.getArenaManager().getArenas());

        if (arenas.isEmpty()) {
            sender.sendMessage("§7No arenas are loaded.");
            return;
        }

        sender.sendMessage("§eLoaded Arenas:");
        for (Arena arena : arenas) {
            sender.sendMessage("§7- " + arena.getName() + " §8[" + (arena.isRunning() ? "§aRunning" : "§cStopped") + "§8]");
        }
    }

}
