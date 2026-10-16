package cloud.mineplay.mobarena.equipment;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Captured and decoded exclusively on the server thread; encoded bytes are safe for database I/O. */
final class PlayerSnapshot {
    private static final int FORMAT = 1;
    private ItemStack[] storage;
    private ItemStack[] armor;
    private ItemStack offhand;
    private int slot, level, totalExperience, food, fireTicks, freezeTicks, air;
    private float experience, saturation, exhaustion, fallDistance, walkSpeed, flySpeed;
    private double health, absorption;
    private boolean allowFlight, flying, invulnerable, restoreConsumables;
    private GameMode gameMode;
    private Location location;
    private List<PotionEffect> effects;

    static PlayerSnapshot capture(Player player, boolean restoreConsumables) {
        PlayerSnapshot result = new PlayerSnapshot();
        result.storage = copies(player.getInventory().getStorageContents());
        result.armor = copies(player.getInventory().getArmorContents());
        result.offhand = copy(player.getInventory().getItemInOffHand());
        result.slot = player.getInventory().getHeldItemSlot();
        result.level = player.getLevel();
        result.totalExperience = player.getTotalExperience();
        result.experience = player.getExp();
        result.health = player.getHealth();
        result.absorption = player.getAbsorptionAmount();
        result.food = player.getFoodLevel();
        result.saturation = player.getSaturation();
        result.exhaustion = player.getExhaustion();
        result.fireTicks = player.getFireTicks();
        result.freezeTicks = player.getFreezeTicks();
        result.air = player.getRemainingAir();
        result.fallDistance = player.getFallDistance();
        result.walkSpeed = player.getWalkSpeed();
        result.flySpeed = player.getFlySpeed();
        result.allowFlight = player.getAllowFlight();
        result.flying = player.isFlying();
        result.invulnerable = player.isInvulnerable();
        result.gameMode = player.getGameMode();
        result.location = player.getLocation().clone();
        result.effects = List.copyOf(player.getActivePotionEffects());
        result.restoreConsumables = restoreConsumables;
        return result;
    }

    /** Builds an immutable durable replacement before changing any live inventory. */
    PlayerSnapshot restorationTarget(Player player, boolean classEquipment, Location exit) throws IOException {
        PlayerSnapshot target = decode(encode());
        if (!classEquipment) {
            target.storage = copies(player.getInventory().getStorageContents());
            target.armor = copies(player.getInventory().getArmorContents());
            target.offhand = copy(player.getInventory().getItemInOffHand());
            target.slot = player.getInventory().getHeldItemSlot();
            if (restoreConsumables) refundConsumables(target);
        }
        if (exit != null) target.location = exit.clone();
        return target;
    }

    private void refundConsumables(PlayerSnapshot target) {
        // Root arena protection disallows pickup, dropping, containers and all item transfer.
        // Remove containers created by consumption before refunding the consumed inputs.
        ItemStack[] originalInventory = Arrays.copyOf(storage, storage.length + 1);
        originalInventory[storage.length] = offhand;
        List<ItemStack> original = ammunitionAwareContents(originalInventory);
        ItemStack[] combined = Arrays.copyOf(target.storage, target.storage.length + 1);
        combined[target.storage.length] = target.offhand;
        for (Material container : List.of(Material.GLASS_BOTTLE, Material.BOWL, Material.BUCKET)) {
            int keep = original.stream().filter(item -> !empty(item) && item.getType() == container)
                    .mapToInt(ItemStack::getAmount).sum();
            for (int i = 0; i < combined.length; i++) {
                ItemStack item = combined[i];
                if (empty(item) || item.getType() != container) continue;
                int retained = Math.min(keep, item.getAmount());
                keep -= retained;
                if (retained == 0) combined[i] = null;
                else item.setAmount(retained);
            }
        }
        List<ItemStack> representatives = new ArrayList<>();
        for (ItemStack source : original) {
            if (empty(source) || !consumable(source.getType())
                    || representatives.stream().anyMatch(item -> item.isSimilar(source))) continue;
            representatives.add(source);
            int missing = refundableAmount(originalInventory, combined, source);
            for (ItemStack item : combined) {
                if (missing == 0) break;
                if (!empty(item) && item.isSimilar(source)) {
                    int add = Math.min(missing, item.getMaxStackSize() - item.getAmount());
                    item.setAmount(item.getAmount() + add);
                    missing -= add;
                }
            }
            for (int i = 0; i < combined.length && missing > 0; i++) {
                if (!empty(combined[i])) continue;
                combined[i] = source.clone();
                int add = Math.min(missing, source.getMaxStackSize());
                combined[i].setAmount(add);
                missing -= add;
            }
            if (missing != 0) throw new IllegalStateException("No safe inventory space for consumable restoration");
        }
        target.storage = Arrays.copyOf(combined, storage.length);
        target.offhand = combined[storage.length];
    }

    static int refundableAmount(ItemStack[] original, ItemStack[] current, ItemStack source) {
        int initial = ammunitionAwareContents(original).stream().filter(item -> item.isSimilar(source))
                .mapToInt(ItemStack::getAmount).sum();
        int remaining = ammunitionAwareContents(current).stream().filter(item -> item.isSimilar(source))
                .mapToInt(ItemStack::getAmount).sum();
        return Math.max(0, initial - remaining);
    }

    private static List<ItemStack> ammunitionAwareContents(ItemStack[] inventory) {
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack item : inventory) {
            if (empty(item)) continue;
            contents.add(item);
            if (item.getItemMeta() instanceof CrossbowMeta crossbow && !crossbow.getChargedProjectiles().isEmpty()) {
                // Multishot stores several projectiles but consumes one ammunition item.
                // Loading an arrow transfers it into the crossbow; it has not been spent.
                ItemStack paidAmmunition = crossbow.getChargedProjectiles().getFirst().clone();
                paidAmmunition.setAmount(1);
                contents.add(paidAmmunition);
            }
        }
        return contents;
    }

    private static boolean consumable(Material material) {
        return material.isEdible() || switch (material) {
            case POTION, SPLASH_POTION, LINGERING_POTION, MILK_BUCKET, ARROW, TIPPED_ARROW, SPECTRAL_ARROW -> true;
            default -> false;
        };
    }

    void apply(Player player) {
        if (location == null || location.getWorld() == null) {
            throw new IllegalStateException("The saved recovery world is unavailable");
        }
        replaceInventory(player);
        player.setGameMode(gameMode);
        player.setTotalExperience(totalExperience);
        player.setLevel(level);
        player.setExp(experience);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.addPotionEffects(effects);
        var maximum = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(Math.max(0.1, Math.min(health, maximum == null ? 20 : maximum.getValue())));
        player.setAbsorptionAmount(absorption);
        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
        // Do not send an eliminated player back into damage immediately after recovery.
        player.setFireTicks(Math.min(0, fireTicks));
        player.setFreezeTicks(0);
        player.setRemainingAir(Math.max(air, player.getMaximumAir()));
        player.setFallDistance(0);
        player.setWalkSpeed(walkSpeed);
        player.setFlySpeed(flySpeed);
        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && flying);
        player.setInvulnerable(invulnerable);
        if (!player.teleport(location)) throw new IllegalStateException("Recovery teleport was cancelled");
        player.updateInventory();
    }

    /** Kept separate from world/entity restoration so replacement replay can be verified in isolation. */
    void replaceInventory(Player player) {
        player.closeInventory();
        player.setItemOnCursor(null);
        // Exact replacement, never addItem: replay while quarantined cannot duplicate a snapshot.
        player.getInventory().setStorageContents(copies(storage));
        player.getInventory().setArmorContents(copies(armor));
        player.getInventory().setItemInOffHand(copy(offhand));
        player.getInventory().setHeldItemSlot(slot);
    }

    byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(FORMAT);
            writeItems(out, storage);
            writeItems(out, armor);
            writeItem(out, offhand);
            out.writeInt(slot); out.writeInt(level); out.writeInt(totalExperience); out.writeFloat(experience);
            out.writeDouble(health); out.writeDouble(absorption);
            out.writeInt(food); out.writeFloat(saturation); out.writeFloat(exhaustion);
            out.writeInt(fireTicks); out.writeInt(freezeTicks); out.writeInt(air); out.writeFloat(fallDistance);
            out.writeFloat(walkSpeed); out.writeFloat(flySpeed);
            out.writeBoolean(allowFlight); out.writeBoolean(flying); out.writeBoolean(invulnerable);
            out.writeBoolean(restoreConsumables); out.writeUTF(gameMode.name());
            out.writeUTF(location.getWorld().getUID().toString());
            out.writeUTF(location.getWorld().getName());
            out.writeDouble(location.getX()); out.writeDouble(location.getY()); out.writeDouble(location.getZ());
            out.writeFloat(location.getYaw()); out.writeFloat(location.getPitch());
            out.writeInt(effects.size());
            for (PotionEffect effect : effects) {
                out.writeUTF(effect.getType().getKey().toString());
                out.writeInt(effect.getDuration()); out.writeInt(effect.getAmplifier());
                out.writeBoolean(effect.isAmbient()); out.writeBoolean(effect.hasParticles());
                out.writeBoolean(effect.hasIcon());
            }
        }
        return bytes.toByteArray();
    }

    static PlayerSnapshot decode(byte[] bytes) throws IOException {
        PlayerSnapshot result = new PlayerSnapshot();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != FORMAT) throw new IOException("Unsupported equipment snapshot format");
            result.storage = readItems(in, 36); result.armor = readItems(in, 4); result.offhand = readItem(in);
            result.slot = in.readInt(); result.level = in.readInt(); result.totalExperience = in.readInt();
            result.experience = in.readFloat(); result.health = in.readDouble(); result.absorption = in.readDouble();
            result.food = in.readInt(); result.saturation = in.readFloat(); result.exhaustion = in.readFloat();
            result.fireTicks = in.readInt(); result.freezeTicks = in.readInt(); result.air = in.readInt();
            result.fallDistance = in.readFloat(); result.walkSpeed = in.readFloat(); result.flySpeed = in.readFloat();
            result.allowFlight = in.readBoolean(); result.flying = in.readBoolean(); result.invulnerable = in.readBoolean();
            result.restoreConsumables = in.readBoolean(); result.gameMode = GameMode.valueOf(in.readUTF());
            UUID worldId = UUID.fromString(in.readUTF());
            String worldName = in.readUTF();
            World world = Bukkit.getWorld(worldId);
            // A recreated world with the same name is not the original world: preserve quarantine.
            if (world == null) throw new IOException("Recovery world is unavailable: " + worldName + " (" + worldId + ")");
            result.location = new Location(world, in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat());
            int count = in.readInt();
            if (count < 0 || count > 256) throw new IOException("Invalid snapshot effect count");
            result.effects = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.fromString(in.readUTF()));
                int duration = in.readInt(), amplifier = in.readInt();
                boolean ambient = in.readBoolean(), particles = in.readBoolean(), icon = in.readBoolean();
                if (type == null) throw new IOException("Unknown saved potion effect");
                result.effects.add(new PotionEffect(type, duration, amplifier, ambient, particles, icon));
            }
            if (in.available() != 0) throw new IOException("Trailing data in equipment snapshot");
        }
        return result;
    }

    private static void writeItems(DataOutputStream out, ItemStack[] items) throws IOException {
        out.writeInt(items.length);
        for (ItemStack item : items) writeItem(out, item);
    }
    private static void writeItem(DataOutputStream out, ItemStack item) throws IOException {
        byte[] bytes = empty(item) ? new byte[0] : item.serializeAsBytes();
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static ItemStack[] readItems(DataInputStream in, int expected) throws IOException {
        int size = in.readInt();
        if (size != expected) throw new IOException("Invalid snapshot inventory size");
        ItemStack[] items = new ItemStack[size];
        for (int i = 0; i < size; i++) items[i] = readItem(in);
        return items;
    }
    private static ItemStack readItem(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 16_777_216) throw new IOException("Invalid saved item length");
        if (size == 0) return null;
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new IOException("Truncated saved item");
        return ItemStack.deserializeBytes(bytes);
    }
    private static ItemStack copy(ItemStack item) { return empty(item) ? null : item.clone(); }
    private static ItemStack[] copies(ItemStack[] items) { return Arrays.stream(items).map(PlayerSnapshot::copy).toArray(ItemStack[]::new); }
    static boolean empty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() == 0; }
}
