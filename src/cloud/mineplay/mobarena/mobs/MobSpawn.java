package cloud.mineplay.mobarena.mobs;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;

/** Weighted equipment alternatives per slot; all arena equipment has zero drop chance. */
public final class MobSpawn {
    private MobSpawn() {}
    public static boolean roll(double chance) { return ThreadLocalRandom.current().nextDouble() < chance; }
    public static void protectFromSunlight(Mob mob) {
        if (mob instanceof Zombie zombie) zombie.setShouldBurnInDay(false);
        if (mob instanceof AbstractSkeleton skeleton) skeleton.setShouldBurnInDay(false);
        if (mob instanceof Phantom phantom) phantom.setShouldBurnInDay(false);
    }
    public static void equip(Mob mob, ConfigurationSection config) {
        protectFromSunlight(mob);
        if (mob instanceof Zombie zombie) {
            zombie.setBaby(roll(config == null ? 0 : config.getDouble("baby-chance",0)));
            zombie.setCanBreakDoors(false);
        }
        EntityEquipment equipment = mob.getEquipment();
        if (equipment == null) return;
        equipment.clear();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HAND,EquipmentSlot.OFF_HAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)) {
            ItemStack item = null;
            String path = "equipment."+slot.name().toLowerCase(Locale.ROOT);
            if (config != null && config.contains(path)) {
                List<Map<?,?>> entries = config.getMapList(path);
                double choice = ThreadLocalRandom.current().nextDouble(), cumulative = 0;
                for (Map<?,?> entry : entries) {
                    cumulative += Double.parseDouble(String.valueOf(entry.get("chance")));
                    if (choice < cumulative) {
                        Material material = Material.valueOf(String.valueOf(entry.get("material")).toUpperCase(Locale.ROOT));
                        if (material != Material.AIR) item = new ItemStack(material);
                        break;
                    }
                }
            } else if (slot == EquipmentSlot.HAND) {
                Material weapon = defaultWeapon(mob.getType());
                if (weapon != null) item = new ItemStack(weapon);
            }
            equipment.setItem(slot,item);
            equipment.setDropChance(slot,0);
        }
    }
    /** CUSTOM spawns skip vanilla equipment. Explicit hand lists (including []) override this fallback. */
    static Material defaultWeapon(EntityType type) {
        if (type == null) return null;
        return switch (type) {
            case SKELETON, STRAY -> Material.BOW;
            case PILLAGER -> Material.CROSSBOW;
            case WITHER_SKELETON -> Material.STONE_SWORD;
            case VINDICATOR -> Material.IRON_AXE;
            case PIGLIN, ZOMBIFIED_PIGLIN -> Material.GOLDEN_SWORD;
            case PIGLIN_BRUTE -> Material.GOLDEN_AXE;
            case VEX -> Material.IRON_SWORD;
            default -> null;
        };
    }
    public static List<String> validate(ConfigurationSection mobs) {
        List<String> errors = new ArrayList<>();
        if (mobs == null) return List.of("waves.mobs is missing.");
        for (String type : mobs.getKeys(false)) {
            if ("DROWNED".equalsIgnoreCase(type)) continue; // Legacy drowned rules remain inactive.
            ConfigurationSection config = mobs.getConfigurationSection(type);
            if (config == null) continue;
            if (config.contains("rapid-fire")) {
                if (!"SKELETON".equalsIgnoreCase(type)) errors.add(type+".rapid-fire is only supported for SKELETON.");
                try { RapidFireSettings.read(config); }
                catch (IllegalArgumentException exception) { errors.add(type+"."+exception.getMessage()); }
            }
            for (String key : List.of("baby-chance","chicken-jockey-chance")) {
                double chance = config.getDouble(key,0);
                if (!Double.isFinite(chance) || chance < 0 || chance > 1) errors.add(type+"."+key+" must be between 0 and 1.");
            }
            ConfigurationSection equipment = config.getConfigurationSection("equipment");
            if (equipment == null) continue;
            for (String slot : equipment.getKeys(false)) {
                try { EquipmentSlot.valueOf(slot.toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException e) { errors.add("Unsupported mob equipment slot: "+slot); }
                double sum = 0;
                for (Map<?,?> item : equipment.getMapList(slot)) {
                    try {
                        Material material = Material.valueOf(String.valueOf(item.get("material")).toUpperCase(Locale.ROOT));
                        if (!material.isItem() && material != Material.AIR) throw new IllegalArgumentException();
                        double chance = Double.parseDouble(String.valueOf(item.get("chance")));
                        if (!Double.isFinite(chance) || chance < 0 || chance > 1) throw new IllegalArgumentException();
                        sum += chance;
                    } catch (RuntimeException e) { errors.add("Invalid material/chance in "+type+" equipment."+slot); }
                }
                if (sum > 1.00000001) errors.add(type+" equipment."+slot+" chances exceed 1.");
            }
        }
        return errors;
    }
}
