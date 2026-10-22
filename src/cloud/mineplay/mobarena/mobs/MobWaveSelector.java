package cloud.mineplay.mobarena.mobs;

import cloud.mineplay.mobarena.config.ArenaDefinition.MobRule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/** Chooses a complete wave pool once; it never adds a mob outside the arena's unlocked roster. */
public final class MobWaveSelector {
    private MobWaveSelector() {}

    public record Theme(String name, int weight, List<EntityType> mobs) {
        public Theme {
            mobs = List.copyOf(mobs);
            if (name == null || name.isBlank() || name.length() > 48 || name.chars().anyMatch(Character::isISOControl))
                throw invalid("theme name must be 1 to 48 characters without control characters");
            if (weight < 1 || weight > 10000) throw invalid("theme weight must be between 1 and 10000");
            if (mobs.isEmpty() || mobs.size() > 64 || new HashSet<>(mobs).size() != mobs.size())
                throw invalid("each theme needs a nonempty list of unique mob types (maximum 64)");
        }
    }

    public record Settings(boolean enabled, double chance, int minimumWave, int cooldownWaves, List<Theme> themes) {
        public Settings {
            themes = List.copyOf(themes);
            if (!Double.isFinite(chance) || chance < 0 || chance > 1) throw invalid("chance must be between 0 and 1");
            if (minimumWave < 1 || minimumWave > 10000) throw invalid("minimum-wave must be between 1 and 10000");
            if (cooldownWaves < 0 || cooldownWaves > 10000) throw invalid("cooldown-waves must be between 0 and 10000");
            if (themes.size() > 64 || enabled && themes.isEmpty())
                throw invalid("enabled meme rounds need between 1 and 64 configured types");
        }

        public static Settings disabled() { return new Settings(false, .12, 4, 3, List.of()); }

        /** A missing section stays disabled for existing installations. */
        public static Settings read(ConfigurationSection section) {
            if (section == null) return disabled();
            Object enabled = section.get("enabled", false), chance = section.get("chance", .12);
            if (!(enabled instanceof Boolean)) throw invalid("enabled must be true or false");
            if (!(chance instanceof Number probability)) throw invalid("chance must be a number between 0 and 1");
            int minimum = integer(section, "minimum-wave", 4), cooldown = integer(section, "cooldown-waves", 3);
            ConfigurationSection types = section.getConfigurationSection("types");
            if (section.contains("types") && types == null) throw invalid("types must be a YAML configuration section");
            List<Theme> themes = new ArrayList<>();
            if (types != null) for (String key : types.getKeys(false)) {
                ConfigurationSection theme = types.getConfigurationSection(key);
                if (theme == null) throw invalid("types." + key + " must be a YAML configuration section");
                Object name = theme.get("name", key), entries = theme.get("mobs");
                if (!(name instanceof String label)) throw invalid("types." + key + ".name must be text");
                if (!(entries instanceof List<?> list)) throw invalid("types." + key + ".mobs must be a list of mob names");
                List<EntityType> mobs = new ArrayList<>();
                for (Object entry : list) {
                    if (!(entry instanceof String text)) throw invalid("types." + key + ".mobs must contain mob names");
                    try { mobs.add(EntityType.valueOf(text.toUpperCase(Locale.ROOT))); }
                    catch (IllegalArgumentException error) { throw invalid("unknown mob '" + text + "' in types." + key); }
                }
                themes.add(new Theme(label, integer(theme, "weight", 1), mobs));
            }
            return new Settings((Boolean)enabled, probability.doubleValue(), minimum, cooldown, themes);
        }
    }

    public record Selection(String name, List<MobRule> mobs) {
        public Selection { mobs = List.copyOf(mobs); }
        public boolean meme() { return !name.isEmpty(); }
    }

    /** cooldownWaves is the number of ordinary waves required between themed rounds. */
    public static Selection select(Settings settings, List<MobRule> eligible, int wave, int lastMemeWave,
                                   RandomGenerator random) {
        List<MobRule> unlocked = eligible.stream().filter(rule -> rule.unlockWave() <= wave).toList();
        Selection ordinary = new Selection("", unlocked);
        if (!settings.enabled() || wave < settings.minimumWave()
                || lastMemeWave > 0 && wave - lastMemeWave <= settings.cooldownWaves()) return ordinary;
        Set<EntityType> available = new HashSet<>();
        unlocked.forEach(rule -> available.add(rule.type()));
        List<Theme> candidates = settings.themes().stream().filter(theme -> available.containsAll(theme.mobs())).toList();
        if (candidates.isEmpty() || settings.chance() == 0 || random.nextDouble() >= settings.chance()) return ordinary;
        int pick = random.nextInt(candidates.stream().mapToInt(Theme::weight).sum());
        for (Theme theme : candidates) {
            pick -= theme.weight();
            if (pick < 0) return new Selection(theme.name(), unlocked.stream().filter(rule -> theme.mobs().contains(rule.type())).toList());
        }
        throw new IllegalStateException("No meme round selected from a nonempty weighted pool");
    }

    private static int integer(ConfigurationSection section, String path, int fallback) {
        if (!section.contains(path)) return fallback;
        if (!section.isInt(path)) throw invalid(path + " must be an integer");
        return section.getInt(path);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("waves.meme-rounds: " + message + ".");
    }
}
