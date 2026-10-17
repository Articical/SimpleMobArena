package cloud.mineplay.mobarena.config;

import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

/** Validated geometry; never creates worlds or changes blocks. */
public final class ArenaDefinition {
    private static final Set<String> SUPPORTED = Set.of("ZOMBIE", "HUSK", "SKELETON", "STRAY",
        "WITHER_SKELETON", "SPIDER", "CAVE_SPIDER", "BLAZE", "ENDERMAN", "SILVERFISH",
        "ENDERMITE", "PILLAGER", "VINDICATOR", "WITCH", "ZOMBIFIED_PIGLIN", "PIGLIN_BRUTE");
    private static final Set<EntityType> RANGED = Set.of(EntityType.SKELETON, EntityType.STRAY,
        EntityType.BLAZE, EntityType.PILLAGER, EntityType.WITCH, EntityType.WITHER_SKELETON);
    public record MobRule(EntityType type, int unlockWave, int weight, double healthMultiplier) {}
    public final String id, name;
    public final YamlConfiguration config;
    public final World world;
    public final Location pos1, pos2, exit;
    public final List<Location> playerSpawns, mobSpawns;
    public final List<MobRule> mobs = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public final boolean enabled;
    public final int minPlayers, maxPlayers, countdownSeconds, maxWaves, maxActiveMobs, maxMobsPerWave;
    public final int baseMobs, perWavePerPlayer, spawnIntervalTicks, spawnBatchSize, intermissionSeconds, maxWaveSeconds;
    public final double healthIncrease, difficulty;
    public final int healthEvery;
    public final boolean playerHealthScaling;
    public final double healthPerAdditionalPlayer, maxPlayerHealthMultiplier;

    public ArenaDefinition(String id, YamlConfiguration config, ConfigurationSection defaults) {
        this.id = id; this.config = config;
        name = config.getString("name", id);
        enabled = config.getBoolean("enabled", false);
        world = Bukkit.getWorld(config.getString("world", "world"));
        if (world == null) errors.add("Configured world is not loaded. Select a corner in your arena world with /ma admin setpos1 "+id+".");
        pos1 = read(config.getString("pos1")); pos2 = read(config.getString("pos2")); exit = read(config.getString("exit"));
        validateBounds();
        playerSpawns = readList("player-spawns", "Player", "setplayer");
        mobSpawns = readList("mob-spawns", "Mob", "setmob");
        if (playerSpawns.isEmpty()) errors.add("No player spawns. Stand inside and use /ma admin setplayer "+id+".");
        if (mobSpawns.isEmpty()) errors.add("No mob spawns. Stand inside and use /ma admin setmob "+id+".");
        if (exit == null) errors.add("Exit is not set or its world is not loaded. Stand outside and use /ma admin setexit "+id+".");
        else if (contains(exit)) errors.add("Exit "+describe(exit)+" is inside the arena. Stand outside and use /ma admin setexit "+id+".");
        else {
            String problem = SpawnSafety.problem(exit);
            if (problem != null) errors.add("Exit "+describe(exit)+": "+problem+". Use /ma admin setexit "+id+" at a safe outside location.");
        }
        if (config.contains("outsider-exit") && !config.getString("outsider-exit", "").isBlank()) {
            Location outsiderExit = read(config.getString("outsider-exit"));
            String problem = outsiderExit == null ? "location is invalid or its world is not loaded"
                : contains(outsiderExit) ? "location is inside the arena" : SpawnSafety.problem(outsiderExit);
            if (problem != null) errors.add("Outsider exit: "+problem+". Use /ma admin setoutsider "+id+" exit to share the normal exit.");
        }
        minPlayers = integer(config, "min-players", 1, 1, 64);
        maxPlayers = integer(config, "max-players", 4, minPlayers, 64);
        countdownSeconds = integer(config, "countdown-seconds", 15, 0, 600);
        maxWaves = integer(config, "max-waves", defaults.getInt("waves.max-waves", 30), 1, 10000);
        maxActiveMobs = integer(config, "max-active-mobs", defaults.getInt("waves.max-active-mobs", 100), 1, 500);
        maxMobsPerWave = integer(defaults, "waves.max-mobs-per-wave", 500, 1, 5000);
        baseMobs = integer(defaults, "waves.base-mobs", 5, 1, 500);
        perWavePerPlayer = integer(defaults, "waves.mobs-per-wave-per-player", 1, 0, 100);
        spawnIntervalTicks = integer(defaults, "waves.spawn-interval-ticks", 10, 1, 1200);
        spawnBatchSize = integer(defaults, "waves.spawn-batch-size", 2, 1, 20);
        intermissionSeconds = integer(defaults, "waves.intermission-seconds", 8, 0, 300);
        maxWaveSeconds = integer(defaults, "waves.maximum-wave-seconds", 600, 30, 7200);
        healthEvery = integer(defaults, "waves.health-increase-every-waves", 5, 1, 1000);
        healthIncrease = finite(defaults.getDouble("waves.health-increase-percent", 20), 0, 1000, "health-increase-percent") / 100;
        if (defaults.contains("waves.player-health-scaling") && !defaults.isConfigurationSection("waves.player-health-scaling"))
            errors.add("waves.player-health-scaling must be a YAML configuration section.");
        playerHealthScaling = bool(defaults, "waves.player-health-scaling.enabled", true);
        healthPerAdditionalPlayer = number(defaults, "waves.player-health-scaling.additional-player-multiplier", .15, 0, 10);
        maxPlayerHealthMultiplier = number(defaults, "waves.player-health-scaling.maximum-multiplier", 2.5, 1, 20);
        difficulty = finite(config.getDouble("difficulty-multiplier", 1), .1, 10, "difficulty-multiplier");
        ConfigurationSection section = defaults.getConfigurationSection("waves.mobs");
        if (section != null) for (String typeName : section.getKeys(false)) {
            // Old installations retain their config.yml. Retire these rules without
            // making otherwise valid arenas unusable or rewriting their settings.
            if ("CREEPER".equalsIgnoreCase(typeName) || "DROWNED".equalsIgnoreCase(typeName)) continue;
            try {
                EntityType type = EntityType.valueOf(typeName.toUpperCase(Locale.ROOT));
                if (!SUPPORTED.contains(type.name())) throw new IllegalArgumentException();
                ConfigurationSection row = section.getConfigurationSection(typeName);
                if (row == null) throw new IllegalArgumentException();
                int unlockWave = integer(row,"unlock-wave",1,1,10000);
                // Keep the first two waves melee-only, including for existing configuration files.
                // The floor applies to the effective pool; later unlocks and source YAML stay intact.
                if (RANGED.contains(type)) unlockWave = Math.max(3,unlockWave);
                mobs.add(new MobRule(type, unlockWave, integer(row,"weight",1,1,10000),
                    finite(row.getDouble("health-multiplier",1),.1,20,"mob health-multiplier")));
            } catch (IllegalArgumentException e) { errors.add("Unsupported or invalid mob: " + typeName); }
        }
        if (mobs.stream().noneMatch(m -> m.unlockWave() == 1)) errors.add("No melee mob is available at wave 1. Set waves.mobs.ZOMBIE.unlock-wave to 1 in config.yml, then /ma admin reload. Ranged mobs start at wave 3 or later.");
        errors.addAll(cloud.mineplay.mobarena.mobs.MobSpawn.validate(section));
        if (errors.isEmpty()) WorldGuardSupport.validate(this);
    }
    private double finite(double value, double low, double high, String key) {
        if (!Double.isFinite(value) || value < low || value > high) { errors.add(key + " must be between " + low + " and " + high); return low; }
        return value;
    }
    private boolean bool(ConfigurationSection c, String key, boolean fallback) {
        Object value = c.get(key, fallback);
        if (value instanceof Boolean valid) return valid;
        errors.add(key + " must be true or false (a YAML boolean, without quotes).");
        return fallback;
    }
    private double number(ConfigurationSection c, String key, double fallback, double low, double high) {
        Object value = c.get(key, fallback);
        if (value instanceof Number numeric) {
            double result = numeric.doubleValue();
            if (Double.isFinite(result) && result >= low && result <= high) return result;
        }
        errors.add(key + " must be a finite number between " + low + " and " + high + ".");
        return fallback;
    }
    private int integer(ConfigurationSection c, String key, int fallback, int low, int high) {
        int value = c.getInt(key, fallback);
        if ((c.contains(key) && !c.isInt(key)) || value < low || value > high) {
            errors.add(key + " must be an integer between " + low + " and " + high); return low;
        }
        return value;
    }
    private void validateBounds() {
        if (pos1 == null || pos2 == null) {
            errors.add("Set lower and opposite upper boundary corners with /ma admin setpos1 "+id+" and /ma admin setpos2 "+id+".");
            return;
        }
        if (pos1.getWorld() != world || pos2.getWorld() != world)
            errors.add("Boundary corners must be in the same configured world. Reset both corners in the arena world.");
        if (pos1.getBlockY() == pos2.getBlockY())
            errors.add("Boundary corners are both at Y="+pos1.getBlockY()+". Set one low and the opposite corner above the arena with /ma admin setpos2 "+id+".");
        if (pos1.getBlockX() == pos2.getBlockX() || pos1.getBlockZ() == pos2.getBlockZ())
            errors.add("Boundary corners need different X and Z coordinates. Select opposite corners of the arena.");
        if (Math.abs(pos1.getX()-pos2.getX()) > 256 || Math.abs(pos1.getZ()-pos2.getZ()) > 256)
            errors.add("Arena boundary may be at most 256 blocks across X and Z.");
    }
    private void validateSpawn(Location l, String label, String command) {
        String fix = " Use /ma admin "+command+" "+id+" to replace this spawn list.";
        if (world != null && pos1 != null && pos2 != null && !contains(l)) {
            List<String> outside = new ArrayList<>();
            if (l.getWorld() != world) outside.add("world must be "+world.getName());
            else {
                outsideAxis(outside,"X",l.getX(),pos1.getBlockX(),pos2.getBlockX());
                outsideAxis(outside,"Y",l.getY(),pos1.getBlockY(),pos2.getBlockY());
                outsideAxis(outside,"Z",l.getZ(),pos1.getBlockZ(),pos2.getBlockZ());
            }
            errors.add(label+" "+describe(l)+" is outside the boundary ("+String.join(", ",outside)+")."+fix);
        } else {
            String problem = SpawnSafety.problem(l);
            if (problem != null) errors.add(label+" "+describe(l)+": "+problem+"."+fix);
        }
    }
    private static void outsideAxis(List<String> problems,String axis,double value,int a,int b) {
        int low = Math.min(a,b), high = Math.max(a,b);
        if (value < low || value >= high+1.0) problems.add(axis+" must be in blocks "+low+".."+high);
    }
    private static String describe(Location l) {
        return String.format(Locale.ROOT,"%s (%.2f, %.2f, %.2f)",l.getWorld().getName(),l.getX(),l.getY(),l.getZ());
    }
    private List<Location> readList(String key,String label,String command) {
        List<Location> list = new ArrayList<>();
        int index = 0;
        for (String raw : config.getStringList(key)) {
            index++;
            Location l = read(raw);
            if (l == null) errors.add(label+" spawn #"+index+" is invalid or its world is not loaded. Use /ma admin "+command+" "+id+" to replace this spawn list.");
            else { list.add(l); validateSpawn(l,label+" spawn #"+index,command); }
        }
        return List.copyOf(list);
    }
    public boolean contains(Location l) {
        return l != null && world != null && l.getWorld() == world && pos1 != null && pos2 != null
            && l.getX() >= Math.min(pos1.getBlockX(),pos2.getBlockX()) && l.getX() < Math.max(pos1.getBlockX(),pos2.getBlockX())+1
            && l.getY() >= Math.min(pos1.getBlockY(),pos2.getBlockY()) && l.getY() < Math.max(pos1.getBlockY(),pos2.getBlockY())+1
            && l.getZ() >= Math.min(pos1.getBlockZ(),pos2.getBlockZ()) && l.getZ() < Math.max(pos1.getBlockZ(),pos2.getBlockZ())+1;
    }
    public boolean overlaps(ArenaDefinition other) {
        if (world == null || world != other.world || pos1 == null || pos2 == null || other.pos1 == null || other.pos2 == null) return false;
        return Math.min(pos1.getBlockX(),pos2.getBlockX()) <= Math.max(other.pos1.getBlockX(),other.pos2.getBlockX())
            && Math.max(pos1.getBlockX(),pos2.getBlockX()) >= Math.min(other.pos1.getBlockX(),other.pos2.getBlockX())
            && Math.min(pos1.getBlockY(),pos2.getBlockY()) <= Math.max(other.pos1.getBlockY(),other.pos2.getBlockY())
            && Math.max(pos1.getBlockY(),pos2.getBlockY()) >= Math.min(other.pos1.getBlockY(),other.pos2.getBlockY())
            && Math.min(pos1.getBlockZ(),pos2.getBlockZ()) <= Math.max(other.pos1.getBlockZ(),other.pos2.getBlockZ())
            && Math.max(pos1.getBlockZ(),pos2.getBlockZ()) >= Math.min(other.pos1.getBlockZ(),other.pos2.getBlockZ());
    }
    public static boolean safe(Location l) {
        return SpawnSafety.problem(l) == null;
    }
    public static Location read(String raw) {
        if (raw == null) return null;
        try {
            String[] p = raw.split(",");
            World w = Bukkit.getWorld(p[0]);
            if (w == null || (p.length != 4 && p.length != 6)) return null;
            Location l = new Location(w,Double.parseDouble(p[1]),Double.parseDouble(p[2]),Double.parseDouble(p[3]),
                p.length == 6 ? Float.parseFloat(p[4]) : 0, p.length == 6 ? Float.parseFloat(p[5]) : 0);
            l.checkFinite(); return l;
        } catch (RuntimeException e) { return null; }
    }
    public static String write(Location l) {
        return l.getWorld().getName()+","+l.getX()+","+l.getY()+","+l.getZ()+","+l.getYaw()+","+l.getPitch();
    }
}
