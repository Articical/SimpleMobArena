package cloud.mineplay.mobarena.config;

import java.util.*;
import cloud.mineplay.mobarena.mobs.MobWaveSelector;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

/** Validated geometry; never creates worlds or changes blocks. */
public final class ArenaDefinition {
    private static final Set<String> SUPPORTED = Set.of("ZOMBIE", "HUSK", "SKELETON", "STRAY",
        "WITHER_SKELETON", "SPIDER", "CAVE_SPIDER", "BLAZE", "ENDERMAN", "SILVERFISH",
        "ENDERMITE", "PILLAGER", "VINDICATOR", "WITCH", "ZOMBIFIED_PIGLIN", "PIGLIN_BRUTE",
        "CREEPER", "RAVAGER", "SHULKER", "IRON_GOLEM", "BREEZE", "GOAT", "PIGLIN",
        "PHANTOM", "EVOKER", "BEE", "VEX", "LLAMA");
    private static final Set<EntityType> RANGED = Set.of(EntityType.SKELETON, EntityType.STRAY,
        EntityType.BLAZE, EntityType.PILLAGER, EntityType.WITCH, EntityType.WITHER_SKELETON,
        EntityType.SHULKER, EntityType.BREEZE, EntityType.EVOKER, EntityType.PIGLIN, EntityType.PHANTOM,
        EntityType.LLAMA);
    public record MobRule(EntityType type, int unlockWave, int weight, double healthMultiplier) {}
    public final String id, name, mobSet;
    public final ArenaMode mode;
    public final YamlConfiguration config;
    public final World world;
    public final Location pos1, pos2, exit;
    public final List<Location> playerSpawns, mobSpawns, redSpawns, blueSpawns;
    public final List<MobRule> mobs = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public final boolean enabled;
    public final int minPlayers, maxPlayers, countdownSeconds, maxWaves, maxActiveMobs, maxMobsPerWave;
    public final int baseMobs, perWavePerPlayer, spawnIntervalTicks, spawnBatchSize, intermissionSeconds, maxWaveSeconds;
    public final double healthIncrease, difficulty;
    public final int healthEvery;
    public final boolean playerHealthScaling;
    public final double healthPerAdditionalPlayer, maxPlayerHealthMultiplier;
    public final boolean healBetweenWaves, chooseTeams;
    public final int teamSize, duelMaxSeconds;
    public final MobWaveSelector.Settings memeRounds;
    private final Map<EntityType, ConfigurationSection> mobSettings = new EnumMap<>(EntityType.class);

    public ArenaDefinition(String id, YamlConfiguration config, ConfigurationSection defaults) {
        this.id = id; this.config = config;
        name = config.getString("name", id);
        enabled = config.getBoolean("enabled", false);
        mode = readMode();
        if (mode.isDuel() && config.contains("duel") && !config.isConfigurationSection("duel"))
            errors.add("duel must be a YAML configuration section.");
        teamSize = mode == ArenaMode.TEAM_DUEL ? integer(config, "duel.team-size", 2, 1, 16) : 1;
        duelMaxSeconds = mode.isDuel() ? integer(config, "duel.maximum-seconds", 300, 30, 3600) : 300;
        chooseTeams = mode == ArenaMode.TEAM_DUEL && readTeamSelection();
        mobSet = mode.isDuel() ? "" : readMobSet();
        world = Bukkit.getWorld(config.getString("world", "world"));
        if (world == null) errors.add("Configured world is not loaded. Select a corner in your arena world with /ma admin setpos1 "+id+".");
        pos1 = read(config.getString("pos1")); pos2 = read(config.getString("pos2")); exit = read(config.getString("exit"));
        validateBounds();
        redSpawns = mode.isDuel() ? readList("duel.red-spawns", "Red team", "setred") : List.of();
        blueSpawns = mode.isDuel() ? readList("duel.blue-spawns", "Blue team", "setblue") : List.of();
        List<Location> lobbySpawns = readList("player-spawns", "Player", "setplayer");
        if (mode.isDuel() && lobbySpawns.isEmpty()) {
            lobbySpawns = new ArrayList<>(redSpawns);
            lobbySpawns.addAll(blueSpawns);
        }
        playerSpawns = List.copyOf(lobbySpawns);
        mobSpawns = mode.isDuel() ? List.of() : readList("mob-spawns", "Mob", "setmob");
        if (playerSpawns.isEmpty()) errors.add("No player spawns. Stand inside and use /ma admin setplayer "+id+".");
        if (!mode.isDuel() && mobSpawns.isEmpty()) errors.add("No mob spawns. Stand inside and use /ma admin setmob "+id+".");
        if (mode.isDuel() && redSpawns.isEmpty()) errors.add("No red team spawns. Stand inside and use /ma admin setteam "+id+" red.");
        if (mode.isDuel() && blueSpawns.isEmpty()) errors.add("No blue team spawns. Stand inside and use /ma admin setteam "+id+" blue.");
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
        minPlayers = mode.isDuel() ? teamSize * 2 : integer(config, "min-players", 1, 1, 64);
        maxPlayers = mode.isDuel() ? teamSize * 2 : integer(config, "max-players", 4, minPlayers, 64);
        countdownSeconds = integer(config, "countdown-seconds", 15, 0, 600);
        ConfigurationSection waves = effectiveWaves(defaults);
        memeRounds = readMemeRounds(waves);
        maxWaves = !mode.isDuel() && config.contains("max-waves")
            ? integer(config, "max-waves", 30, 1, 10000) : integer(waves, "waves.max-waves", 30, 1, 10000);
        maxActiveMobs = !mode.isDuel() && config.contains("max-active-mobs")
            ? integer(config, "max-active-mobs", 100, 1, 500) : integer(waves, "waves.max-active-mobs", 100, 1, 500);
        maxMobsPerWave = integer(waves, "waves.max-mobs-per-wave", 500, 1, 5000);
        baseMobs = integer(waves, "waves.base-mobs", 5, 1, 500);
        perWavePerPlayer = integer(waves, "waves.mobs-per-wave-per-player", 1, 0, 100);
        spawnIntervalTicks = integer(waves, "waves.spawn-interval-ticks", 10, 1, 1200);
        spawnBatchSize = integer(waves, "waves.spawn-batch-size", 2, 1, 20);
        intermissionSeconds = integer(waves, "waves.intermission-seconds", 8, 0, 300);
        maxWaveSeconds = integer(waves, "waves.maximum-wave-seconds", 600, 30, 7200);
        healthEvery = integer(waves, "waves.health-increase-every-waves", 5, 1, 1000);
        healthIncrease = number(waves, "waves.health-increase-percent", 20, 0, 1000) / 100;
        if (waves.contains("waves.player-health-scaling") && !waves.isConfigurationSection("waves.player-health-scaling"))
            errors.add("waves.player-health-scaling must be a YAML configuration section.");
        playerHealthScaling = bool(waves, "waves.player-health-scaling.enabled", true);
        healthPerAdditionalPlayer = number(waves, "waves.player-health-scaling.additional-player-multiplier", .15, 0, 10);
        maxPlayerHealthMultiplier = number(waves, "waves.player-health-scaling.maximum-multiplier", 2.5, 1, 20);
        healBetweenWaves = bool(waves, "waves.heal-between-waves", true);
        difficulty = finite(config.getDouble("difficulty-multiplier", 1), .1, 10, "difficulty-multiplier");
        ConfigurationSection section = mode.isDuel() ? null : effectiveMobPool(defaults);
        if (section != null) for (String typeName : section.getKeys(false)) {
            // Old installations retain their config.yml. Retire drowned without
            // making otherwise valid arenas unusable or rewriting their settings.
            if ("DROWNED".equalsIgnoreCase(typeName)) continue;
            try {
                EntityType type = EntityType.valueOf(typeName.toUpperCase(Locale.ROOT));
                if (!SUPPORTED.contains(type.name())) throw new IllegalArgumentException();
                ConfigurationSection row = section.getConfigurationSection(typeName);
                if (row == null) throw new IllegalArgumentException();
                mobSettings.put(type, row);
                int unlockWave = integer(row,"unlock-wave",1,1,10000);
                // Keep the first two waves melee-only, including for existing configuration files.
                // The floor applies to the effective pool; later unlocks and source YAML stay intact.
                if (RANGED.contains(type)) unlockWave = Math.max(3,unlockWave);
                mobs.add(new MobRule(type, unlockWave, integer(row,"weight",1,1,10000),
                    number(row,"health-multiplier",1,.1,20)));
                validateMobSettings(typeName, row);
            } catch (IllegalArgumentException e) { errors.add("Unsupported or invalid mob: " + typeName); }
        }
        if (!mode.isDuel()) {
            if (mobs.stream().noneMatch(m -> m.unlockWave() == 1)) errors.add("No melee mob is available at wave 1. Set waves.mobs.ZOMBIE.unlock-wave to 1 in config.yml or the selected arena mob pool, then /ma admin reload. Ranged mobs start at wave 3 or later.");
            errors.addAll(cloud.mineplay.mobarena.mobs.MobSpawn.validate(section));
        }
        if (errors.isEmpty()) WorldGuardSupport.validate(this);
    }
    public ConfigurationSection mobSettings(EntityType type) { return mobSettings.get(type); }

    private MobWaveSelector.Settings readMemeRounds(ConfigurationSection waves) {
        if (mode.isDuel()) return MobWaveSelector.Settings.disabled();
        try {
            if (waves.contains("waves.meme-rounds") && !waves.isConfigurationSection("waves.meme-rounds"))
                throw new IllegalArgumentException("waves.meme-rounds must be a YAML configuration section.");
            MobWaveSelector.Settings settings = MobWaveSelector.Settings.read(waves.getConfigurationSection("waves.meme-rounds"));
            for (MobWaveSelector.Theme theme : settings.themes()) for (EntityType type : theme.mobs())
                if (!SUPPORTED.contains(type.name())) throw new IllegalArgumentException("waves.meme-rounds: unsupported mob " + type + ".");
            return settings;
        } catch (IllegalArgumentException error) {
            errors.add(error.getMessage());
            return MobWaveSelector.Settings.disabled();
        }
    }

    private ArenaMode readMode() {
        Object value = config.get("mode", "MOB_ARENA");
        if (value instanceof String name) {
            try { return ArenaMode.valueOf(name.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { /* Report the supported values below. */ }
        }
        errors.add("mode must be MOB_ARENA, DUEL or TEAM_DUEL.");
        return ArenaMode.MOB_ARENA;
    }

    private boolean readTeamSelection() {
        Object value = config.get("duel.team-selection", "AUTO");
        if (value instanceof String selection) {
            if (selection.equalsIgnoreCase("AUTO")) return false;
            if (selection.equalsIgnoreCase("CHOOSE")) return true;
        }
        errors.add("duel.team-selection must be AUTO or CHOOSE.");
        return false;
    }

    private String readMobSet() {
        Object value = config.get("mob-set", "");
        if (value instanceof String selected && (selected.isBlank() || selected.matches("[A-Za-z0-9_-]+")))
            return selected.isBlank() ? "" : selected;
        errors.add("mob-set must be a named mob set using letters, numbers, underscores or hyphens.");
        return "";
    }

    private ConfigurationSection effectiveWaves(ConfigurationSection defaults) {
        YamlConfiguration result = new YamlConfiguration();
        if (mode.isDuel()) return result;
        ConfigurationSection target = result.createSection("waves");
        for (ConfigurationSection source : List.of(defaults, config)) {
            if (source.contains("waves") && !source.isConfigurationSection("waves")) {
                errors.add("waves must be a YAML configuration section.");
                continue;
            }
            ConfigurationSection section = source.getConfigurationSection("waves");
            if (section != null) merge(section, target, Set.of("mobs"));
        }
        return result;
    }

    private ConfigurationSection effectiveMobPool(ConfigurationSection defaults) {
        ConfigurationSection global = defaults.getConfigurationSection("waves.mobs");
        ConfigurationSection selected = global;
        if (!mobSet.isEmpty()) {
            selected = defaults.getConfigurationSection("mob-sets." + mobSet + ".mobs");
            if (selected == null) errors.add("Unknown or invalid mob-set '" + mobSet + "'. Define mob-sets." + mobSet + ".mobs in config.yml.");
        }
        if (config.contains("waves.mobs")) {
            selected = config.getConfigurationSection("waves.mobs");
            if (selected == null) errors.add("Arena waves.mobs must be a YAML configuration section.");
        }
        if (selected == null) return null;
        ConfigurationSection result = new YamlConfiguration().createSection("mobs");
        Set<String> encountered = new HashSet<>();
        for (String key : selected.getKeys(false)) {
            String type = key.toUpperCase(Locale.ROOT);
            if (type.equals("DROWNED")) continue;
            if (!encountered.add(type)) errors.add("Duplicate mob type: " + type);
            ConfigurationSection row = selected.getConfigurationSection(key);
            if (row == null) { result.set(type, deepCopy(selected.get(key))); continue; }
            ConfigurationSection target = result.createSection(type);
            // Selecting a pool replaces the mob types. Individual rows still inherit
            // the server's equipment, variants and tuning for that same mob type.
            if (global != null) for (String globalKey : global.getKeys(false)) {
                if (!globalKey.equalsIgnoreCase(type)) continue;
                ConfigurationSection base = global.getConfigurationSection(globalKey);
                if (base != null) merge(base, target, Set.of());
                break;
            }
            merge(row, target, Set.of());
        }
        return result;
    }

    private void validateMobSettings(String type, ConfigurationSection row) {
        for (String key : List.of("baby-chance", "chicken-jockey-chance"))
            if (row.contains(key) && !(row.get(key) instanceof Number))
                errors.add(type + "." + key + " must be a number between 0 and 1.");
        if (row.contains("equipment") && !row.isConfigurationSection("equipment")) {
            errors.add(type + ".equipment must be a YAML configuration section.");
            return;
        }
        ConfigurationSection equipment = row.getConfigurationSection("equipment");
        if (equipment == null) return;
        for (String slot : equipment.getKeys(false)) {
            Object value = equipment.get(slot);
            if (!(value instanceof List<?> alternatives)) {
                errors.add(type + ".equipment." + slot + " must be a list of material/chance entries.");
                continue;
            }
            for (Object alternative : alternatives) {
                if (!(alternative instanceof Map<?, ?> item)
                    || !(item.get("material") instanceof String) || !(item.get("chance") instanceof Number))
                    errors.add(type + ".equipment." + slot + " needs a material name and numeric chance in every entry.");
            }
        }
    }

    private static void merge(ConfigurationSection source, ConfigurationSection target, Set<String> excluded) {
        for (String key : source.getKeys(false)) {
            if (excluded.contains(key)) continue;
            Object value = source.get(key);
            if (value instanceof ConfigurationSection child) {
                ConfigurationSection destination = target.getConfigurationSection(key);
                if (destination == null) destination = target.createSection(key);
                merge(child, destination, Set.of());
            } else target.set(key, deepCopy(value));
        }
    }

    private static Object deepCopy(Object value) {
        if (value instanceof List<?> entries) {
            List<Object> copy = new ArrayList<>(entries.size());
            for (Object entry : entries) copy.add(deepCopy(entry));
            return copy;
        }
        if (value instanceof Map<?, ?> entries) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            entries.forEach((key, entry) -> copy.put(key, deepCopy(entry)));
            return copy;
        }
        return value;
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
        String fix = " Use /ma admin "+spawnCommand(command)+" to replace this spawn list.";
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
        Object value = config.get(key, List.of());
        if (!(value instanceof List<?> values)) {
            errors.add(key+" must be a list of locations.");
            return List.of();
        }
        int index = 0;
        for (Object raw : values) {
            index++;
            Location l = raw instanceof String location ? read(location) : null;
            if (l == null) errors.add(label+" spawn #"+index+" is invalid or its world is not loaded. Use /ma admin "+spawnCommand(command)+" to replace this spawn list.");
            else { list.add(l); validateSpawn(l,label+" spawn #"+index,command); }
        }
        return List.copyOf(list);
    }
    private String spawnCommand(String command) {
        return switch (command) {
            case "setred" -> "setteam " + id + " red";
            case "setblue" -> "setteam " + id + " blue";
            default -> command + " " + id;
        };
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
