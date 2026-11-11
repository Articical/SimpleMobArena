package cloud.mineplay.mobarena;

import cloud.mineplay.mobarena.arenas.ArenaManager;
import cloud.mineplay.mobarena.commands.CommandManager;
import cloud.mineplay.mobarena.economy.EconomyService;
import cloud.mineplay.mobarena.economy.PlayerStatistics;
import cloud.mineplay.mobarena.equipment.EquipmentService;
import cloud.mineplay.mobarena.events.ArenaListener;
import cloud.mineplay.mobarena.messages.ArenaMessages;
import cloud.mineplay.mobarena.integration.CitizensArenaNpc;
import cloud.mineplay.mobarena.integration.AchievementIntegration;
import java.util.UUID;
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
    private AchievementIntegration achievements;
    private String achievementFailure;
    private NamespacedKey sessionKey;
    private long ticks;
    @Override public void onEnable() {
        instance = this; sessionKey = new NamespacedKey(this,"session");
        saveDefaultConfig();
        if (!new java.io.File(getDataFolder(),"classes.yml").exists()) saveResource("classes.yml",false);
        if (!new java.io.File(getDataFolder(),"achievements.yml").exists()) saveResource("achievements.yml",false);
        if (!new java.io.File(getDataFolder(),"arenas").exists()) {
            saveResource("arenas/spawn-example.yml",false);
            saveResource("arenas/duel-example.yml",false);
            saveResource("arenas/team-duel-example.yml",false);
            saveResource("arenas/training-example.yml",false);
            saveResource("arenas/raiders-example.yml",false);
            saveResource("arenas/nether-example.yml",false);
            saveResource("arenas/sky-example.yml",false);
        }
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
            getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler public void enabled(org.bukkit.event.server.PluginEnableEvent event) {
                    if (event.getPlugin().getName().equals("MineplayAchievements")) startAchievements();
                }
            },this);
            startAchievements();
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
            getServer().getScheduler().runTaskTimer(this,() -> {
                arenaManager.tick(++ticks);
                arenaListener.tick();
            },1,1);
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
        if (economyService != null) {
            economyService.setAchievementCheckpoint(null);
            economyService.setLiveAchievementCheckpoint(null);
        }
        closeAchievements();
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
    public String achievementStatus() {
        return achievements != null ? achievements.status() : achievementFailure != null ? achievementFailure
            : "MineplayAchievements is not enabled. Arena statistics are still recorded.";
    }
    public void syncAchievements(UUID player) {
        if (achievements == null) throw new IllegalStateException(achievementStatus());
        if (!achievements.requestSync(player)) throw new IllegalStateException(achievementStatus());
    }
    private void startAchievements() {
        if (achievements != null || !getServer().getPluginManager().isPluginEnabled("MineplayAchievements")) return;
        try {
            // Resolve the optional API only here, so its absence cannot link-fail core arena startup.
            Class.forName("uk.co.mineplay.achievements.api.Achievements",false,getClassLoader());
            Class<?> bridge = Class.forName("cloud.mineplay.mobarena.achievements.MineplayAchievementsBridge",true,getClassLoader());
            achievements = (AchievementIntegration) bridge.getConstructor(JavaPlugin.class,EconomyService.class).newInstance(this,economyService);
            achievements.start();
            economyService.setAchievementCheckpoint(achievements::requestSync);
            economyService.setLiveAchievementCheckpoint(achievements::reportLive);
            achievementFailure = null;
        } catch (Exception | LinkageError error) {
            closeAchievements();
            Throwable cause = error instanceof java.lang.reflect.InvocationTargetException invocation ? invocation.getCause() : error;
            achievementFailure = "Achievements unavailable: " + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            getLogger().log(java.util.logging.Level.WARNING,achievementFailure + ". Arena statistics continue to be recorded.",cause);
        }
    }
    private void closeAchievements() {
        AchievementIntegration previous = achievements;
        achievements = null;
        if (previous != null) try { previous.close(); }
        catch (RuntimeException | LinkageError error) {
            getLogger().log(java.util.logging.Level.WARNING,"Could not close the optional achievements integration",error);
        }
    }
    public NamespacedKey sessionKey() { return sessionKey; }
    public long ticks() { return ticks; }
}
