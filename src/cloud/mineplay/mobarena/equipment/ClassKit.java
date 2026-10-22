package cloud.mineplay.mobarena.equipment;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

record ClassKit(ClassChoice choice, ItemStack[] inventory, ItemStack[] armor, ItemStack offhand,
                List<PotionEffect> effects) {
    record LoadResult(Map<String, ClassKit> kits, Map<String, String> errors) {
        LoadResult {
            kits = Collections.unmodifiableMap(new LinkedHashMap<>(kits));
            errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
        }
    }

    static Map<String, ClassKit> load(File file) {
        LoadResult result = loadAvailable(file);
        if (!result.errors().isEmpty()) throw new IllegalArgumentException("Invalid classes in " + file.getName() + ": "
                + String.join("; ", result.errors().values()));
        return result.kits();
    }

    /** A bad class never becomes a partial kit and never prevents other classes from loading. */
    static LoadResult loadAvailable(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        Map<?, ?> rawClasses;
        try {
            String source = Files.readString(file.toPath());
            // Bukkit permits duplicate YAML keys. Reject those before it can overwrite a class or item.
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object raw = new Yaml(new SafeConstructor(options)).load(source);
            if (!(raw instanceof Map<?, ?> document) || !(document.get("classes") instanceof Map<?, ?> values) || values.isEmpty()) {
                throw new IllegalArgumentException("classes must be a non-empty section in " + file.getAbsolutePath());
            }
            rawClasses = values;
            validateRaw(raw, file.getAbsolutePath(), new IdentityHashMap<>(), false);
            yaml.load(new StringReader(source));
        } catch (IOException | InvalidConfigurationException | YAMLException failure) {
            throw new IllegalArgumentException("Could not load " + file.getAbsolutePath() + ": " + failure.getMessage(), failure);
        }
        ConfigurationSection classes = yaml.getConfigurationSection("classes");
        if (classes == null) throw new IllegalArgumentException("classes must be a section in " + file.getAbsolutePath());
        Map<String, ClassKit> kits = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> definitions = new LinkedHashMap<>();
        rawClasses.forEach((id, definition) -> definitions.put(String.valueOf(id), definition));
        List<String> identifiers = rawClasses.keySet().stream().map(String::valueOf).toList();
        Map<String, Integer> occurrences = new HashMap<>();
        identifiers.forEach(id -> occurrences.merge(id.toLowerCase(Locale.ROOT), 1, Integer::sum));
        for (String id : identifiers) {
            String normalized = id.toLowerCase(Locale.ROOT);
            if (occurrences.get(normalized) > 1) {
                errors.put(id, "Duplicate class identifier at classes." + id + "; every declaration of '" + normalized + "' is disabled");
                continue;
            }
            try {
                if (!id.matches("[A-Za-z0-9_-]{1,64}") || id.equalsIgnoreCase("personal")) {
                    throw new IllegalArgumentException("Invalid class identifier at classes." + id);
                }
                Object rawDefinition = definitions.get(id);
                if (!(rawDefinition instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected a section at classes." + id);
                validateRaw(rawDefinition, "classes." + id, new IdentityHashMap<>(), true);
                kits.put(normalized, read(id, optionalSection(classes, id)));
            } catch (IllegalArgumentException failure) {
                errors.put(id, "Class '" + id + "' is disabled: " + failure.getMessage());
            }
        }
        return new LoadResult(kits, errors);
    }

    private static void validateRaw(Object value, String path, IdentityHashMap<Object, Boolean> ancestors, boolean requireValues) {
        // Bukkit removes explicit YAML nulls. Check before that conversion can silently drop a kit item or setting.
        if (value == null && requireValues) throw new IllegalArgumentException("Missing value at " + path + "; omit optional settings instead of leaving them empty");
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) return;
        if (ancestors.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic YAML alias at " + path);
        if (value instanceof Map<?, ?> map) map.forEach((key, child) -> validateRaw(child, path + "." + key, ancestors, requireValues));
        else {
            List<?> list = (List<?>) value;
            for (int index = 0; index < list.size(); index++) validateRaw(list.get(index), path + "." + index, ancestors, requireValues);
        }
        ancestors.remove(value);
    }

    private static ClassKit read(String id, ConfigurationSection section) {
        if (section == null) throw new IllegalArgumentException("Missing class section classes." + id);
        long requiredKills = requiredKills(section);
        Object configuredName = section.get("display-name", id);
        if (!(configuredName instanceof String displayName) || displayName.isBlank() || displayName.length() > 64
                || displayName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("display-name must contain 1-64 visible characters at " + section.getCurrentPath());
        }
        Material icon = Material.IRON_SWORD;
        if (section.contains("icon")) {
            icon = Material.matchMaterial(section.getString("icon", ""));
            if (icon == null || icon.isAir() || !icon.isItem()) {
                throw new IllegalArgumentException("Class icon must be an item material at " + section.getCurrentPath());
            }
        }
        ItemStack[] inventory = new ItemStack[36];
        ConfigurationSection slots = optionalSection(section, "items");
        if (slots != null) {
            Set<Integer> seen = new HashSet<>();
            for (String key : slots.getKeys(false)) {
                int slot;
                try { slot = Integer.parseInt(key); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("Item slot must be 0-35 at " + slots.getCurrentPath() + "." + key); }
                if (slot < 0 || slot > 35 || !seen.add(slot)) throw new IllegalArgumentException("Item slot must be unique and 0-35 at " + slots.getCurrentPath() + "." + key);
                inventory[slot] = item(optionalSection(slots, key));
            }
        }
        ConfigurationSection armorSection = optionalSection(section, "armor");
        if (armorSection != null && !Set.of("boots", "leggings", "chestplate", "helmet").containsAll(armorSection.getKeys(false))) {
            throw new IllegalArgumentException("Unknown armor slot at " + armorSection.getCurrentPath());
        }
        ItemStack[] armor = armorSection == null ? new ItemStack[4] : new ItemStack[] { item(optionalSection(armorSection, "boots")),
                item(optionalSection(armorSection, "leggings")), item(optionalSection(armorSection, "chestplate")),
                item(optionalSection(armorSection, "helmet")) };
        List<PotionEffect> effects = new ArrayList<>();
        ConfigurationSection effectSection = optionalSection(section, "effects");
        if (effectSection != null) {
            Set<NamespacedKey> seen = new HashSet<>();
            for (String key : effectSection.getKeys(false)) {
                ConfigurationSection settings = optionalSection(effectSection, key);
                int amplifier = integer(settings, "amplifier", 0, 0, 10);
                int duration = integer(settings, "duration-ticks", -1, -1, Integer.MAX_VALUE);
                if (duration == 0) throw new IllegalArgumentException("duration-ticks must be positive or -1 at " + settings.getCurrentPath());
                NamespacedKey namespaced = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
                PotionEffectType type = namespaced == null ? null : PotionEffectType.getByKey(namespaced);
                if (type == null) throw new IllegalArgumentException("Unknown potion effect at " + effectSection.getCurrentPath() + "." + key);
                if (!seen.add(namespaced)) throw new IllegalArgumentException("Duplicate potion effect at " + effectSection.getCurrentPath() + "." + key);
                effects.add(new PotionEffect(type, duration, amplifier, false, false, true));
            }
        }
        return new ClassKit(new ClassChoice(id.toLowerCase(Locale.ROOT), displayName, icon, requiredKills), inventory, armor,
                item(optionalSection(section, "offhand")), List.copyOf(effects));
    }

    private static ConfigurationSection optionalSection(ConfigurationSection parent, String key) {
        if (!parent.contains(key)) return null;
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) throw new IllegalArgumentException("Expected a section at " + parent.getCurrentPath() + "." + key);
        return section;
    }

    private static int integer(ConfigurationSection section, String key, int fallback, int min, int max) {
        Object raw = section.get(key);
        if (raw == null) return fallback;
        try {
            long value;
            if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) value = ((Number) raw).longValue();
            else if (raw instanceof BigInteger big) value = big.longValueExact();
            else if (raw instanceof String text && text.matches("-?[0-9]+")) value = Long.parseLong(text);
            else throw new IllegalArgumentException();
            if (value < min || value > max) throw new IllegalArgumentException();
            return (int) value;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IllegalArgumentException(key + " must be a whole number from " + min + " to " + max + " at " + section.getCurrentPath());
        }
    }

    static long requiredKills(ConfigurationSection section) {
        Object value = section.get("required-kills");
        // Old configurations remain open until the owner adds a threshold.
        if (value == null) return 0;
        try {
            long result;
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                result = ((Number) value).longValue();
            } else if (value instanceof BigInteger integer) {
                result = integer.longValueExact();
            } else if (value instanceof String text && text.matches("[0-9]+")) {
                result = Long.parseLong(text);
            } else throw new IllegalArgumentException();
            if (result < 0) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IllegalArgumentException("required-kills must be a whole number from 0 to " + Long.MAX_VALUE
                    + " at " + section.getCurrentPath());
        }
    }

    private static ItemStack item(ConfigurationSection section) {
        if (section == null) return null;
        Material material = Material.matchMaterial(section.getString("material", ""));
        if (material == null) {
            throw new IllegalArgumentException("Unknown item material at " + section.getCurrentPath());
        }
        KitPotion potion = KitPotion.read(section, material);
        if (material.isAir() || !material.isItem()) throw new IllegalArgumentException("Material is not an item at " + section.getCurrentPath());
        int amount = integer(section, "amount", 1, 1, material.getMaxStackSize());
        ItemStack item = new ItemStack(material, amount);
        if (potion != null) potion.apply(item);
        ConfigurationSection enchantments = optionalSection(section, "enchantments");
        if (enchantments != null) {
            for (String key : enchantments.getKeys(false)) {
                NamespacedKey namespaced = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
                Enchantment enchantment = namespaced == null ? null : Enchantment.getByKey(namespaced);
                int level = enchantments.getInt(key);
                if (enchantment == null || level < 1 || level > enchantment.getMaxLevel()
                        || !enchantment.canEnchantItem(item)) {
                    throw new IllegalArgumentException("Invalid enchantment " + key + " at " + section.getCurrentPath());
                }
                item.addEnchantment(enchantment, level);
            }
        }
        return item;
    }

    void apply(Player player) {
        player.closeInventory();
        player.setItemOnCursor(null);
        player.getInventory().setStorageContents(cloneItems(inventory));
        player.getInventory().setArmorContents(cloneItems(armor));
        player.getInventory().setItemInOffHand(offhand == null ? null : offhand.clone());
        player.getInventory().setHeldItemSlot(0);
        player.setGameMode(GameMode.SURVIVAL);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.addPotionEffects(effects);
        player.updateInventory();
    }

    private static ItemStack[] cloneItems(ItemStack[] items) {
        return java.util.Arrays.stream(items).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }
}
