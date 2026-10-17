package cloud.mineplay.mobarena;

import cloud.mineplay.mobarena.arenas.ArenaManager;
import cloud.mineplay.mobarena.commands.CommandManager;
import cloud.mineplay.mobarena.economy.EconomyService;
import cloud.mineplay.mobarena.economy.PlayerStatistics;
import cloud.mineplay.mobarena.equipment.EquipmentService;
import cloud.mineplay.mobarena.events.ArenaListener;
import cloud.mineplay.mobarena.messages.ArenaMessages;
import cloud.mineplay.mobarena.integration.CitizensArenaNpc;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class SimpleMobArena extends JavaPlugin {
    private static SimpleMobArena instance;
    private ArenaManager arenaManager;
    private EquipmentService equipmentService;
    private EconomyService economyService;
    private ArenaListener arenaListener;
    private AutoCloseable placeholders;
    private CitizensArenaNpc citizensArenaNpc;
    private NamespacedKey sessionKey;
    private long ticks;
    @Override public void onEnable() {
        instance = this; sessionKey = new NamespacedKey(this,"session");
        saveDefaultConfig();
        if (!new java.io.File(getDataFolder(),"classes.yml").exists()) saveResource("classes.yml",false);
        if (!new java.io.File(getDataFolder(),"arenas").exists()) saveResource("arenas/spawn-example.yml",false);
        try {
            // Do not let Bukkit's permissive YAML loading silently substitute defaults.
            new org.bukkit.configuration.file.YamlConfiguration().load(new java.io.File(getDataFolder(),"config.yml"));
            equipmentService = new EquipmentService(this);
            economyService = new EconomyService(this);
            equipmentService.setLifetimeKillsLookup(id -> economyService.stats(id).thenApply(PlayerStatistics::kills));
            economyService.reload(getConfig().getConfigurationSection("economy"));
            arenaManager = new ArenaManager(this); arenaManager.loadArenas();
            arenaListener = new ArenaListener(this);
            getServer().getPluginManager().registerEvents(arenaListener,this);
            CommandManager commands = new CommandManager(this);
            java.util.Objects.requireNonNull(getCommand("mobarena")).setExecutor(commands);
            getCommand("mobarena").setTabCompleter(commands);
            try {
                citizensArenaNpc = new CitizensArenaNpc(this,commands::openMenu);
                citizensArenaNpc.start();
            } catch (Exception | LinkageError optionalFailure) {
                if (citizensArenaNpc != null) citizensArenaNpc.close();
                citizensArenaNpc = null;
                getLogger().log(java.util.logging.Level.WARNING,"Optional Citizens arena menus are unavailable; ordinary arena commands remain usable",optionalFailure);
            }
            // Nonpersistent tags also allow removal of stragglers saved by another plugin.
            for (World world : getServer().getWorlds()) for (var entity : world.getEntities())
                if (entity.getPersistentDataContainer().has(sessionKey,PersistentDataType.STRING)) entity.remove();
            getServer().getScheduler().runTaskTimer(this,() -> arenaManager.tick(++ticks),1,1);
            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                try {
                    var expansion = new cloud.mineplay.mobarena.integration.MobArenaExpansion(this);
                    expansion.start(); placeholders = expansion;
                    getLogger().info("PlaceholderAPI expansion registered: simplemobarena");
                } catch (Exception | LinkageError optionalFailure) {
                    getLogger().log(java.util.logging.Level.WARNING,"Optional PlaceholderAPI integration unavailable",optionalFailure);
                }
            }
            getLogger().info("SimpleMobArena enabled for Paper 26.3 / Java 25.");
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE,"RECOVERY ONLY: storage/configuration startup failed. Restore database access and restart before allowing logins.",e);
            getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler(priority=EventPriority.HIGHEST) public void login(PlayerLoginEvent event) {
                    event.disallow(PlayerLoginEvent.Result.KICK_OTHER,"Arena equipment recovery is unavailable. Contact an administrator.");
                }
            },this);
            for (var player : getServer().getOnlinePlayers()) player.kick(Component.text("Arena recovery is unavailable. Please wait for an administrator."));
            if (getCommand("mobarena") != null) getCommand("mobarena").setExecutor((sender,command,label,args) -> {
                sender.sendMessage(ArenaMessages.error("Recovery-only mode. Check the server log, restore database access, then restart.")); return true;
            });
        }
    }
    @Override public void onDisable() {
        if (citizensArenaNpc != null) citizensArenaNpc.close();
        if (placeholders != null) try { placeholders.close(); } catch (Exception error) { getLogger().warning("Could not unregister placeholders: "+error.getMessage()); }
        if (arenaManager != null) arenaManager.stopAllArenas();
        if (equipmentService != null) equipmentService.shutdown();
        if (economyService != null) economyService.close();
    }
    public static SimpleMobArena getInstance() { return instance; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public EquipmentService getEquipmentService() { return equipmentService; }
    public EconomyService getEconomyService() { return economyService; }
    public ArenaListener getArenaListener() { return arenaListener; }
    public CitizensArenaNpc getCitizensArenaNpc() { return citizensArenaNpc; }
    public NamespacedKey sessionKey() { return sessionKey; }
    public long ticks() { return ticks; }
}
