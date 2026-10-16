package cloud.mineplay.mobarena.config;

import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

/** Validated geometry; never creates worlds or changes blocks. */
public final class ArenaDefinition {
    private static final Set<String> SUPPORTED = Set.of("ZOMBIE", "HUSK", "DROWNED", "SKELETON", "STRAY",
        "WITHER_SKELETON", "SPIDER", "CAVE_SPIDER", "CREEPER", "BLAZE", "ENDERMAN", "SILVERFISH",
        "ENDERMITE", "PILLAGER", "VINDICATOR", "WITCH", "ZOMBIFIED_PIGLIN", "PIGLIN_BRUTE");
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

    public ArenaDefinition(String id, YamlConfiguration config, ConfigurationSection defaults) {
        this.id = id; this.config = config;
        name = config.getString("name", id);
        enabled = config.getBoolean("enabled", false);
        world = Bukkit.getWorld(config.getString("world", "world"));
        if (world == null) errors.add("Configured world is not loaded.");
        pos1 = read(config.getString("pos1")); pos2 = read(config.getString("pos2")); exit = read(config.getString("exit"));
        if (pos1 == null || pos2 == null) errors.add("Set both boundary corners (pos1 and pos2).");
        else if (pos1.getWorld() != world || pos2.getWorld() != world || pos1.getBlockX() == pos2.getBlockX()
            || pos1.getBlockY() == pos2.getBlockY() || pos1.getBlockZ() == pos2.getBlockZ()
            || Math.abs(pos1.getX()-pos2.getX()) > 256 || Math.abs(pos1.getZ()-pos2.getZ()) > 256)
            errors.add("Boundaries must enclose a non-zero volume in the configured world, at most 256 blocks across.");
        playerSpawns = readList("player-spawns"); mobSpawns = readList("mob-spawns");
        if (playerSpawns.isEmpty()) errors.add("Add at least one player spawn.");
        if (mobSpawns.isEmpty()) errors.add("Add at least one mob spawn.");
        for (Location l : playerSpawns) validateSpawn(l, "Player");
        for (Location l : mobSpawns) validateSpawn(l, "Mob");
        if (exit == null) errors.add("Set an exit location in a loaded world.");
        else if (contains(exit)) errors.add("Exit must be outside the arena boundary.");
        else if (!safe(exit)) errors.add("Exit needs two clear blocks above a solid floor.");
        if (config.contains("outsider-exit") && !config.getString("outsider-exit", "").isBlank()) {
            Location outsiderExit = read(config.getString("outsider-exit"));
            if (outsiderExit == null || contains(outsiderExit) || !safe(outsiderExit))
                errors.add("outsider-exit must be a safe fixed location outside the arena.");
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
        difficulty = finite(config.getDouble("difficulty-multiplier", 1), .1, 10, "difficulty-multiplier");
        ConfigurationSection section = defaults.getConfigurationSection("waves.mobs");
        if (section != null) for (String typeName : section.getKeys(false)) {
            try {
                EntityType type = EntityType.valueOf(typeName.toUpperCase(Locale.ROOT));
                if (!SUPPORTED.contains(type.name())) throw new IllegalArgumentException();
                ConfigurationSection row = section.getConfigurationSection(typeName);
                if (row == null) throw new IllegalArgumentException();
                mobs.add(new MobRule(type, integer(row,"unlock-wave",1,1,10000), integer(row,"weight",1,1,10000),
                    finite(row.getDouble("health-multiplier",1),.1,20,"mob health-multiplier")));
            } catch (IllegalArgumentException e) { errors.add("Unsupported or invalid mob: " + typeName); }
        }
        if (mobs.stream().noneMatch(m -> m.unlockWave() == 1)) errors.add("At least one supported mob must unlock at wave 1.");
        errors.addAll(cloud.mineplay.mobarena.mobs.MobSpawn.validate(section));
        if (errors.isEmpty()) WorldGuardSupport.validate(this);
    }
    private double finite(double value, double low, double high, String key) {
        if (!Double.isFinite(value) || value < low || value > high) { errors.add(key + " must be between " + low + " and " + high); return low; }
        return value;
    }
    private int integer(ConfigurationSection c, String key, int fallback, int low, int high) {
        int value = c.getInt(key, fallback);
        if ((c.contains(key) && !c.isInt(key)) || value < low || value > high) {
            errors.add(key + " must be an integer between " + low + " and " + high); return low;
        }
        return value;
    }
    private void validateSpawn(Location l, String label) {
        if (!contains(l)) errors.add(label + " spawn outside boundary: " + write(l));
        else if (!safe(l)) errors.add(label + " spawn needs two clear blocks above a solid floor: " + write(l));
    }
    private List<Location> readList(String key) {
        List<Location> list = new ArrayList<>();
        for (String raw : config.getStringList(key)) {
            Location l = read(raw);
            if (l == null) errors.add("Invalid " + key + " location: " + raw); else list.add(l);
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
        return l != null && l.getWorld() != null && l.getY() >= l.getWorld().getMinHeight()+1 && l.getY()+2 < l.getWorld().getMaxHeight()
            && !hazard(l.getBlock().getType()) && !hazard(l.clone().add(0,1,0).getBlock().getType())
            && !hazard(l.clone().subtract(0,1,0).getBlock().getType())
            && l.getBlock().isPassable() && !l.getBlock().isLiquid()
            && l.clone().add(0,1,0).getBlock().isPassable() && !l.clone().add(0,1,0).getBlock().isLiquid()
            && l.clone().subtract(0,1,0).getBlock().getType().isSolid();
    }
    private static boolean hazard(Material material) {
        return switch(material) {
            case FIRE, SOUL_FIRE, LAVA, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE, CACTUS, SWEET_BERRY_BUSH,
                POWDER_SNOW, WITHER_ROSE, END_PORTAL, NETHER_PORTAL -> true;
            default -> false;
        };
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
