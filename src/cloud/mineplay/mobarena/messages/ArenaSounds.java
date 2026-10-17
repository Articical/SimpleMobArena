package cloud.mineplay.mobarena.messages;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Cached sound settings. Call on the main thread with the intended participants only. */
public final class ArenaSounds {
    public enum Cue {
        JOIN("minecraft:block.note_block.pling", 0.55F, 1.2F),
        COUNTDOWN("minecraft:block.note_block.hat", 0.5F, 1.2F),
        MATCH_START("minecraft:block.note_block.bell", 0.7F, 1.0F),
        WAVE_START("minecraft:block.note_block.chime", 0.6F, 1.1F),
        WAVE_COMPLETE("minecraft:entity.experience_orb.pickup", 0.7F, 1.2F),
        MOB_KILL("minecraft:entity.experience_orb.pickup", 0.4F, 1.7F),
        ELIMINATED("minecraft:block.note_block.bass", 0.7F, 0.7F),
        MATCH_END("minecraft:entity.player.levelup", 0.7F, 1.0F),
        CANCELLED("minecraft:block.note_block.didgeridoo", 0.5F, 0.8F);

        private final String sound;
        private final float volume;
        private final float pitch;

        Cue(String sound, float volume, float pitch) {
            this.sound = sound;
            this.volume = volume;
            this.pitch = pitch;
        }

        private String path() { return "sounds." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    }

    private final Logger logger;
    private final boolean enabled;
    private final Map<Cue, Settings> settings = new EnumMap<>(Cue.class);

    public ArenaSounds(JavaPlugin plugin) {
        logger = plugin.getLogger();
        ConfigurationSection config = plugin.getConfig();
        enabled = bool(config, "sounds.enabled", true);
        for (Cue cue : Cue.values()) {
            String path = cue.path();
            settings.put(cue, new Settings(bool(config, path + ".enabled", true),
                    sound(config, path + ".sound", cue.sound),
                    number(config, path + ".volume", cue.volume, 0.0, 2.0),
                    number(config, path + ".pitch", cue.pitch, 0.5, 2.0)));
        }
    }

    public void play(Cue cue, Player player) {
        Settings selected = settings.get(cue);
        if (!enabled || selected == null || !selected.enabled() || player == null) return;
        try {
            if (player.isOnline()) player.playSound(player.getLocation(), selected.sound(), SoundCategory.MASTER,
                    selected.volume(), selected.pitch());
        } catch (RuntimeException error) {
            warning("Could not play arena sound " + cue + "; match progression continues.", error);
        }
    }

    public void play(Cue cue, Collection<? extends Player> players) {
        if (players == null) return;
        for (Player player : players) play(cue, player);
    }

    private boolean bool(ConfigurationSection config, String path, boolean fallback) {
        Object value = config.get(path, fallback);
        if (value instanceof Boolean result) return result;
        invalid(path, fallback);
        return fallback;
    }

    private String sound(ConfigurationSection config, String path, String fallback) {
        Object value = config.get(path, fallback);
        if (value instanceof String name) {
            name = name.strip();
            // Validate syntax only: resource packs can supply keys outside the vanilla sound registry.
            if (name.indexOf(':') > 0 && NamespacedKey.fromString(name) != null) return name;
        }
        invalid(path, fallback);
        return fallback;
    }

    private float number(ConfigurationSection config, String path, float fallback, double minimum, double maximum) {
        Object value = config.get(path, fallback);
        if (value instanceof Number number) {
            double result = number.doubleValue();
            if (Double.isFinite(result) && result >= minimum && result <= maximum) return (float) result;
        }
        invalid(path, fallback);
        return fallback;
    }

    private void invalid(String path, Object fallback) {
        warning("Invalid " + path + "; using " + fallback + ".", null);
    }

    private void warning(String message, RuntimeException error) {
        try {
            if (error == null) logger.warning(message);
            else logger.log(Level.WARNING, message, error);
        } catch (RuntimeException ignored) { /* Optional presentation must not interrupt the match. */ }
    }

    private record Settings(boolean enabled, String sound, float volume, float pitch) { }
}
