package cloud.mineplay.mobarena.equipment;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

record ClassKit(String id, ItemStack[] inventory, ItemStack[] armor, ItemStack offhand,
                List<PotionEffect> effects) {
    static Map<String, ClassKit> load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection classes = yaml.getConfigurationSection("classes");
        if (classes == null || classes.getKeys(false).isEmpty()) throw new IllegalArgumentException("classes.yml has no classes");
        Map<String, ClassKit> kits = new LinkedHashMap<>();
        for (String id : classes.getKeys(false)) {
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || id.equalsIgnoreCase("personal")) {
                throw new IllegalArgumentException("Invalid class identifier: " + id);
            }
            ConfigurationSection section = classes.getConfigurationSection(id);
            if (section == null) throw new IllegalArgumentException("Invalid class section " + id);
            ItemStack[] inventory = new ItemStack[36];
            ConfigurationSection slots = section.getConfigurationSection("items");
            if (slots != null) {
                for (String key : slots.getKeys(false)) {
                    int slot = Integer.parseInt(key);
                    if (slot < 0 || slot > 35) throw new IllegalArgumentException(id + " item slot must be 0-35: " + key);
                    inventory[slot] = item(slots.getConfigurationSection(key));
                }
            }
            ItemStack[] armor = new ItemStack[] { item(section.getConfigurationSection("armor.boots")),
                    item(section.getConfigurationSection("armor.leggings")), item(section.getConfigurationSection("armor.chestplate")),
                    item(section.getConfigurationSection("armor.helmet")) };
            List<PotionEffect> effects = new ArrayList<>();
            ConfigurationSection effectSection = section.getConfigurationSection("effects");
            if (effectSection != null) {
                for (String key : effectSection.getKeys(false)) {
                    NamespacedKey namespaced = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
                    PotionEffectType type = namespaced == null ? null : PotionEffectType.getByKey(namespaced);
                    if (type == null) throw new IllegalArgumentException("Unknown potion effect in " + id + ": " + key);
                    int amplifier = effectSection.getInt(key + ".amplifier", 0);
                    int duration = effectSection.getInt(key + ".duration-ticks", -1);
                    if (amplifier < 0 || amplifier > 10 || duration == 0 || duration < -1) {
                        throw new IllegalArgumentException("Invalid potion effect settings in " + id + ": " + key);
                    }
                    effects.add(new PotionEffect(type, duration, amplifier, false, false, true));
                }
            }
            String normalized = id.toLowerCase(Locale.ROOT);
            if (kits.put(normalized, new ClassKit(normalized, inventory, armor,
                    item(section.getConfigurationSection("offhand")), List.copyOf(effects))) != null) {
                throw new IllegalArgumentException("Duplicate class id: " + id);
            }
        }
        return Map.copyOf(kits);
    }

    private static ItemStack item(ConfigurationSection section) {
        if (section == null) return null;
        Material material = Material.matchMaterial(section.getString("material", ""));
        if (material == null || material.isAir() || !material.isItem()) {
            throw new IllegalArgumentException("Unknown item material at " + section.getCurrentPath());
        }
        int amount = section.getInt("amount", 1);
        if (amount < 1 || amount > material.getMaxStackSize()) {
            throw new IllegalArgumentException("Invalid stack amount at " + section.getCurrentPath());
        }
        ItemStack item = new ItemStack(material, amount);
        ConfigurationSection enchantments = section.getConfigurationSection("enchantments");
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
