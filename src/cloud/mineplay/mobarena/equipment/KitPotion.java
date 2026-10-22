package cloud.mineplay.mobarena.equipment;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Validates kit potion contents before an ItemStack is created. */
record KitPotion(PotionType baseType, List<PotionEffect> effects, Color color) {
    static KitPotion read(ConfigurationSection item, Material material) {
        if (material == Material.LINGERING_POTION) {
            throw invalid(item, "lingering potions are disabled in arenas; use POTION or SPLASH_POTION");
        }
        boolean potionMaterial = material == Material.POTION || material == Material.SPLASH_POTION;
        if (!potionMaterial) {
            if (item.contains("potion")) throw invalid(item, "potion settings require POTION or SPLASH_POTION");
            return null;
        }
        ConfigurationSection section = item.getConfigurationSection("potion");
        if (section == null) throw invalid(item, "potion items need a potion section with base-type or effects");
        requireKeys(section, Set.of("base-type", "effects", "color"));

        PotionType base = PotionType.WATER;
        if (section.contains("base-type")) {
            Object rawType = section.get("base-type");
            if (!(rawType instanceof String text) || text.isBlank()) throw invalid(section, "base-type must name a Paper PotionType");
            try {
                base = PotionType.valueOf(text.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw invalid(section, "unknown base-type: " + text);
            }
        }

        List<PotionEffect> effects = new ArrayList<>();
        if (section.contains("effects")) {
            ConfigurationSection effectSection = section.getConfigurationSection("effects");
            if (effectSection == null || effectSection.getKeys(false).isEmpty()) throw invalid(section, "effects must contain at least one potion effect");
            Set<NamespacedKey> seen = new HashSet<>();
            for (String effectName : effectSection.getKeys(false)) {
                NamespacedKey key = NamespacedKey.fromString(effectName.toLowerCase(Locale.ROOT));
                PotionEffectType type = key == null ? null : Registry.MOB_EFFECT.get(key);
                if (type == null) throw invalid(effectSection, "unknown potion effect: " + effectName);
                if (!seen.add(key)) throw invalid(effectSection, "duplicate potion effect: " + effectName);
                ConfigurationSection settings = effectSection.getConfigurationSection(effectName);
                if (settings == null) throw invalid(effectSection, "effect settings must be a section: " + effectName);
                requireKeys(settings, Set.of("amplifier", "duration-ticks", "ambient", "particles", "icon"));
                int amplifier = integer(settings, "amplifier", 0, 0, 10);
                int duration = integer(settings, "duration-ticks", null, 1, Integer.MAX_VALUE);
                effects.add(new PotionEffect(type, duration, amplifier,
                        bool(settings, "ambient", false), bool(settings, "particles", true), bool(settings, "icon", true)));
            }
        }
        if (!section.contains("base-type") && effects.isEmpty()) throw invalid(section, "set base-type or at least one custom effect");

        Color color = null;
        if (section.contains("color")) {
            Object rawColor = section.get("color");
            if (!(rawColor instanceof String text) || !text.matches("#[0-9A-Fa-f]{6}")) {
                throw invalid(section, "color must be a quoted '#RRGGBB' value");
            }
            color = Color.fromRGB(Integer.parseInt(text.substring(1), 16));
        }
        return new KitPotion(base, List.copyOf(effects), color);
    }

    void apply(ItemStack item) {
        if (!(item.getItemMeta() instanceof PotionMeta meta)) {
            throw new IllegalArgumentException("Potion item does not have potion metadata: " + item.getType());
        }
        meta.setBasePotionType(baseType);
        meta.clearCustomEffects();
        for (PotionEffect effect : effects) meta.addCustomEffect(effect, true);
        if (color != null) meta.setColor(color);
        if (!item.setItemMeta(meta)) throw new IllegalArgumentException("Could not apply potion contents to " + item.getType());
    }

    private static void requireKeys(ConfigurationSection section, Set<String> allowed) {
        for (String key : section.getKeys(false)) {
            if (!allowed.contains(key)) throw invalid(section, "unknown potion setting: " + key);
        }
    }

    private static boolean bool(ConfigurationSection section, String key, boolean fallback) {
        Object value = section.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        throw invalid(section, key + " must be true or false");
    }

    private static int integer(ConfigurationSection section, String key, Integer fallback, int min, int max) {
        Object raw = section.get(key);
        if (raw == null && fallback != null) return fallback;
        try {
            long value;
            if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
                value = ((Number) raw).longValue();
            } else if (raw instanceof BigInteger big) {
                value = big.longValueExact();
            } else if (raw instanceof String text && text.matches("[0-9]+")) {
                value = Long.parseLong(text);
            } else throw new IllegalArgumentException();
            if (value < min || value > max) throw new IllegalArgumentException();
            return (int) value;
        } catch (IllegalArgumentException | ArithmeticException malformed) {
            throw invalid(section, key + " must be a whole number from " + min + " to " + max);
        }
    }

    private static IllegalArgumentException invalid(ConfigurationSection section, String message) {
        return new IllegalArgumentException(message + " at " + section.getCurrentPath());
    }
}
