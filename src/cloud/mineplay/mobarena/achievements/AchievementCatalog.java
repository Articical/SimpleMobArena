package cloud.mineplay.mobarena.achievements;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import uk.co.mineplay.achievements.api.AchievementDefinition;
import uk.co.mineplay.achievements.api.ProgressSource;
import uk.co.mineplay.achievements.api.Rarity;

/** Provider-owned numeric definitions. Parsing never calls the achievements service or database. */
public final class AchievementCatalog {
    public static final String NAMESPACE = "mineplay_mobarena";
    public static final String OWNER = "SimpleMobArena";
    private static final List<String> MOB_TYPES = List.of("ZOMBIE", "HUSK", "SKELETON", "STRAY",
        "WITHER_SKELETON", "SPIDER", "CAVE_SPIDER", "BLAZE", "ENDERMAN", "SILVERFISH",
        "ENDERMITE", "PILLAGER", "VINDICATOR", "WITCH", "ZOMBIFIED_PIGLIN", "PIGLIN_BRUTE",
        "CREEPER", "RAVAGER", "SHULKER", "IRON_GOLEM", "BREEZE", "GOAT", "PIGLIN",
        "PHANTOM", "EVOKER", "BEE", "VEX", "LLAMA");
    private static final Set<String> SOURCE_KEYS;
    static {
        Set<String> sources = new LinkedHashSet<>(List.of("total_kills", "matches_played", "highest_wave", "duels_played", "duel_wins"));
        MOB_TYPES.forEach(type -> sources.add(type.toLowerCase(Locale.ROOT) + "_kills"));
        SOURCE_KEYS = Collections.unmodifiableSet(sources);
    }

    private final boolean enabled;
    private final List<ProgressSource> sources;
    private final List<AchievementDefinition> definitions;

    private AchievementCatalog(boolean enabled, List<AchievementDefinition> definitions) {
        this.enabled = enabled;
        this.sources = SOURCE_KEYS.stream().map(source -> new ProgressSource(id(source), OWNER)).toList();
        this.definitions = List.copyOf(definitions);
    }

    public boolean enabled() { return enabled; }
    public List<ProgressSource> sources() { return sources; }
    public List<AchievementDefinition> definitions() { return definitions; }
    public static Set<String> sourceKeys() { return SOURCE_KEYS; }
    public static List<String> mobTypes() { return MOB_TYPES; }

    public static String id(String local) {
        if (local == null || !local.matches("[a-z0-9_-]+(?:/[a-z0-9_-]+)*") || local.length() > 120)
            throw invalid("Invalid local achievement/source id: " + local);
        return NAMESPACE + ":" + local;
    }

    public static AchievementCatalog load(File file) {
        try {
            String source = java.nio.file.Files.readString(file.toPath());
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object raw = new Yaml(new SafeConstructor(options)).load(source);
            if (!(raw instanceof Map<?, ?>)) throw invalid("The document must be a YAML mapping.");
            validateRaw(raw, new java.util.IdentityHashMap<>(), 0);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(source);
            return read(yaml);
        } catch (IOException | InvalidConfigurationException | org.yaml.snakeyaml.error.YAMLException failure) {
            throw new IllegalArgumentException("Cannot load " + file.getName() + ": " + failure.getMessage(), failure);
        }
    }

    public static AchievementCatalog read(ConfigurationSection config) {
        boolean enabled = bool(config, "enabled", true);
        int revision = (int) whole(config, "revision", 1, 1, Integer.MAX_VALUE);
        boolean announce = bool(config, "announce", true);
        Map<String, AchievementDefinition> definitions = new LinkedHashMap<>();
        ConfigurationSection explicit = section(config, "achievements");
        if (explicit != null) for (String key : explicit.getKeys(false)) {
            ConfigurationSection row = requiredSection(explicit, key);
            String source = text(row, "source", null);
            if (!SOURCE_KEYS.contains(source)) throw invalid("Unknown progress source at achievements." + key + ": " + source);
            put(definitions, definition(key, row, source, null, revision, announce, List.of(), null));
        }
        ConfigurationSection tiers = section(config, "mob-kill-tiers");
        if (tiers != null && !tiers.getKeys(false).isEmpty()) {
            List<String> types = mobTypes(config);
            if (tiers.getKeys(false).size() > 10) throw invalid("At most 10 mob-kill-tiers are allowed.");
            for (String type : types) {
                String previous = null;
                long previousTarget = 0;
                for (String tier : tiers.getKeys(false)) {
                    ConfigurationSection row = requiredSection(tiers, tier);
                    String local = type.toLowerCase(Locale.ROOT) + "/" + tier;
                    String source = type.toLowerCase(Locale.ROOT) + "_kills";
                    AchievementDefinition definition = definition(local, row, source, mobName(type), revision, announce,
                        previous == null ? List.of() : List.of(previous), previous);
                    if (definition.threshold() <= previousTarget) throw invalid("mob-kill-tiers targets must increase in file order.");
                    put(definitions, definition);
                    previous = definition.id();
                    previousTarget = definition.threshold();
                }
            }
        }
        if (enabled && definitions.isEmpty()) throw invalid("An enabled catalogue needs at least one achievement or mob-kill-tier.");
        if (definitions.size() > 512) throw invalid("At most 512 achievement definitions are allowed.");
        return new AchievementCatalog(enabled, ordered(definitions));
    }

    private static AchievementDefinition definition(String localId, ConfigurationSection row, String source, String mob,
            int revision, boolean announce, List<String> defaultPrerequisites, String defaultParent) {
        String name = text(row, "name", null);
        String description = text(row, "description", mob == null ? "" : "Kill {target} {mob_plural} in the arena.");
        long target = whole(row, "target", null, 1, Long.MAX_VALUE);
        if (mob != null) {
            name = name.replace("{mob}", mob).replace("{target}", Long.toString(target));
            description = description.replace("{mob}", mob).replace("{mob_plural}", plural(mob)).replace("{target}", Long.toString(target));
        }
        Rarity rarity;
        try { rarity = Rarity.valueOf(text(row, "rarity", "COMMON").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw invalid("Invalid rarity for " + localId); }
        List<String> prerequisites = row.contains("prerequisites") ? identifiers(row, "prerequisites") : defaultPrerequisites;
        String parent = row.contains("parent") ? id(text(row, "parent", null)) : defaultParent;
        return new AchievementDefinition(id(localId), OWNER, "Mob Arena", name, description, rarity,
            whole(row, "points", rarity.defaultPoints(), 0, 1_000_000), id(source), target, prerequisites,
            bool(row, "hidden", false), null, bool(row, "announce", announce), List.of(),
            text(row, "icon", mob == null ? "minecraft:iron_sword" : "minecraft:bone"), parent,
            (int) whole(row, "revision", revision, 1, Integer.MAX_VALUE));
    }

    private static List<String> identifiers(ConfigurationSection row, String key) {
        Object value = row.get(key);
        if (!(value instanceof List<?> list) || list.size() > 32) throw invalid(key + " must be a list of at most 32 local ids.");
        List<String> result = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof String local)) throw invalid(key + " must contain local ids.");
            String full = id(local);
            if (result.contains(full)) throw invalid("Duplicate " + key + " id: " + local);
            result.add(full);
        }
        return List.copyOf(result);
    }

    private static void validateRaw(Object value, java.util.IdentityHashMap<Object, Boolean> path, int depth) {
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) return;
        if (depth > 32 || path.put(value, Boolean.TRUE) != null) throw invalid("Nested or recursive YAML is not supported.");
        if (value instanceof Map<?, ?> map) for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.contains("."))
                throw invalid("Configuration keys must be text without dots.");
            validateRaw(entry.getValue(), path, depth + 1);
        }
        else for (Object entry : (List<?>) value) validateRaw(entry, path, depth + 1);
        path.remove(value);
    }

    private static List<String> mobTypes(ConfigurationSection config) {
        if (!config.contains("mob-types")) return MOB_TYPES;
        if (!(config.get("mob-types") instanceof List<?> entries)) throw invalid("mob-types must be a list of supported mob types.");
        List<String> result = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof String value)) throw invalid("mob-types must contain mob names.");
            String type = value.toUpperCase(Locale.ROOT);
            if (!MOB_TYPES.contains(type) || result.contains(type)) throw invalid("Unsupported or duplicate mob-types entry: " + value);
            result.add(type);
        }
        return List.copyOf(result);
    }

    private static String mobName(String type) {
        String[] words = type.toLowerCase(Locale.ROOT).split("_");
        for (int i = 0; i < words.length; i++) words[i] = Character.toUpperCase(words[i].charAt(0)) + words[i].substring(1);
        return String.join(" ", words);
    }

    private static String plural(String mob) {
        String name = mob.toLowerCase(Locale.ROOT);
        return switch (name) {
            case "enderman" -> "endermen";
            case "silverfish" -> "silverfish";
            case "witch", "vex" -> name + "es";
            default -> name + "s";
        };
    }

    private static void put(Map<String, AchievementDefinition> definitions, AchievementDefinition value) {
        if (definitions.putIfAbsent(value.id(), value) != null) throw invalid("Duplicate achievement id: " + value.id());
    }

    private static List<AchievementDefinition> ordered(Map<String, AchievementDefinition> definitions) {
        List<AchievementDefinition> result = new ArrayList<>();
        Set<String> visiting = new HashSet<>(), complete = new HashSet<>();
        for (String id : definitions.keySet()) visit(id, definitions, visiting, complete, result);
        return result;
    }

    private static void visit(String id, Map<String, AchievementDefinition> definitions, Set<String> visiting,
            Set<String> complete, List<AchievementDefinition> result) {
        if (complete.contains(id)) return;
        AchievementDefinition value = definitions.get(id);
        if (value == null) throw invalid("Unknown prerequisite or parent: " + id);
        if (!visiting.add(id)) throw invalid("Achievement dependency cycle at " + id);
        for (String prerequisite : value.prerequisites()) visit(prerequisite, definitions, visiting, complete, result);
        if (value.parent() != null) visit(value.parent(), definitions, visiting, complete, result);
        visiting.remove(id); complete.add(id); result.add(value);
    }

    private static ConfigurationSection section(ConfigurationSection config, String key) {
        if (!config.contains(key)) return null;
        return requiredSection(config, key);
    }

    private static ConfigurationSection requiredSection(ConfigurationSection config, String key) {
        ConfigurationSection section = config.getConfigurationSection(key);
        if (section == null) throw invalid(key + " must be a YAML section.");
        return section;
    }

    private static boolean bool(ConfigurationSection config, String key, boolean fallback) {
        Object value = config.get(key, fallback);
        if (!(value instanceof Boolean result)) throw invalid(key + " must be true or false.");
        return result;
    }

    private static String text(ConfigurationSection config, String key, String fallback) {
        Object value = config.get(key, fallback);
        if (!(value instanceof String result) || (result.isBlank() && !"".equals(fallback))
                || result.length() > 512 || result.chars().anyMatch(Character::isISOControl))
            throw invalid(key + " must be text, at most 512 characters, without control characters.");
        return result;
    }

    private static long whole(ConfigurationSection config, String key, Number fallback, long min, long max) {
        Object value = config.get(key, fallback);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))
            throw invalid(key + " must be a whole number between " + min + " and " + max + ".");
        long result = ((Number) value).longValue();
        if (result < min || result > max) throw invalid(key + " must be between " + min + " and " + max + ".");
        return result;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("achievements.yml: " + message);
    }
}
