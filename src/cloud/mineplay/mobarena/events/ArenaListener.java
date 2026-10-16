package cloud.mineplay.mobarena.events;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.cevents.ArenaWaveCompleteEvent;
import cloud.mineplay.mobarena.misc.LootItem;

public class ArenaListener implements Listener{
	
	private final SimpleMobArena plugin;

    public ArenaListener(SimpleMobArena plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        for (Arena arena : plugin.getArenaManager().getArenas()) {
            if (arena.getPlayers().contains(player)) {
                // Delay removal until respawn
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    arena.removePlayer(player);
                }, 1L);
                break;
            }
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        for (Arena arena : plugin.getArenaManager().getArenas()) {
            if (arena.getPlayers().contains(event.getPlayer())) {
                arena.removePlayer(event.getPlayer());
                Bukkit.getLogger().info("[SimpleMobArena] " + event.getPlayer().getName() + " left the arena due to disconnect.");
                break;
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        UUID mobId = event.getEntity().getUniqueId();

        for (Arena arena : plugin.getArenaManager().getArenas()) {
            if (arena.getActiveMobs().remove(mobId)) {

                // Drop loot immediately
                addRandomLoot(event, arena.getCurrentWave());

                // If no mobs left, fire the event
                if (arena.getActiveMobs().isEmpty()) {
                    Bukkit.getPluginManager().callEvent(new ArenaWaveCompleteEvent(arena, arena.getCurrentWave()));
                }
                break;
            }
        }
    }
    
    private final List<LootItem> lootTable = List.of(
    	    // Tier 1 (Wave 1+)
    	    new LootItem(Material.STONE_SWORD, 10, 1),
    	    new LootItem(Material.BOW, 8, 1),
    	    new LootItem(Material.LEATHER_HELMET, 6, 1),
    	    new LootItem(Material.LEATHER_CHESTPLATE, 6, 1),
    	    new LootItem(Material.LEATHER_LEGGINGS, 6, 1),
    	    new LootItem(Material.LEATHER_BOOTS, 6, 1),

    	    // Tier 2 (Wave 4+)
    	    new LootItem(Material.IRON_SWORD, 6, 4),
    	    new LootItem(Material.CHAINMAIL_HELMET, 5, 4),
    	    new LootItem(Material.CHAINMAIL_CHESTPLATE, 5, 4),
    	    new LootItem(Material.CHAINMAIL_LEGGINGS, 5, 4),
    	    new LootItem(Material.CHAINMAIL_BOOTS, 5, 4),

    	    // Tier 3 (Wave 7+)
    	    new LootItem(Material.IRON_HELMET, 4, 7),
    	    new LootItem(Material.IRON_CHESTPLATE, 4, 7),
    	    new LootItem(Material.IRON_LEGGINGS, 4, 7),
    	    new LootItem(Material.IRON_BOOTS, 4, 7),
    	    new LootItem(Material.IRON_SWORD, 4, 7),
    	    new LootItem(Material.CROSSBOW, 3, 7),
    	    new LootItem(Material.DIAMOND_SWORD, 2, 7),
    	    new LootItem(Material.DIAMOND_HELMET, 2, 7),
    	    new LootItem(Material.DIAMOND_CHESTPLATE, 2, 7),
    	    new LootItem(Material.DIAMOND_LEGGINGS, 2, 7),
    	    new LootItem(Material.DIAMOND_BOOTS, 2, 7),

    	    // Tier 4 (Wave 12+)
    	    new LootItem(Material.NETHERITE_SWORD, 1, 12),
    	    new LootItem(Material.NETHERITE_HELMET, 1, 12),
    	    new LootItem(Material.NETHERITE_CHESTPLATE, 1, 12),
    	    new LootItem(Material.NETHERITE_LEGGINGS, 1, 12),
    	    new LootItem(Material.NETHERITE_BOOTS, 1, 12)
    );
    
    private final List<Enchantment> combatEnchants = List.of(
    	    Enchantment.SHARPNESS,
    	    Enchantment.PIERCING,
    	    Enchantment.PUNCH,
    	    Enchantment.PROTECTION,
    	    Enchantment.PROJECTILE_PROTECTION,
    	    Enchantment.KNOCKBACK,
    	    Enchantment.FIRE_ASPECT,
    	    Enchantment.BANE_OF_ARTHROPODS
    	);
    
    private LootItem getRandomWeightedLoot(int wave) {
        List<LootItem> available = lootTable.stream()
            .filter(item -> wave >= item.getMinWave())
            .toList();

        int totalWeight = available.stream().mapToInt(LootItem::getWeight).sum();
        int random = (int)(Math.random() * totalWeight);
        int cumulative = 0;

        for (LootItem item : available) {
            cumulative += item.getWeight();
            if (random < cumulative) {
                return item;
            }
        }
        return null; // Should not happen
    }
    
    private void addRandomLoot(EntityDeathEvent event, int wave) {
        LivingEntity entity = event.getEntity();

        if (Math.random() < 0.4) { // 30% chance overall
            // 50% chance: consumables, 50% chance: equipment/book
            boolean dropConsumable = Math.random() < 0.65;

            if (dropConsumable) {
                Material[] stackables = {
                    Material.ARROW,
                    Material.APPLE,
                    Material.BREAD
                };
                Material chosen = stackables[(int)(Math.random() * stackables.length)];
                int amount = 1 + (int)(Math.random() * 4);
                entity.getWorld().dropItemNaturally(entity.getLocation(), new ItemStack(chosen, amount));
            } else {
                // 10% chance for enchantment book if wave >= 5
                if (wave >= 5 && Math.random() < 0.1) {
                    Enchantment enchant = combatEnchants.get((int)(Math.random() * combatEnchants.size()));
                    ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
                    EnchantmentStorageMeta meta = (EnchantmentStorageMeta)book.getItemMeta();
                    meta.addStoredEnchant(enchant, 1 + (int)(Math.random() * 2), true);
                    book.setItemMeta(meta);
                    entity.getWorld().dropItemNaturally(entity.getLocation(), book);
                } else {
                    LootItem loot = getRandomWeightedLoot(wave);
                    if (loot != null) {
                        entity.getWorld().dropItemNaturally(entity.getLocation(), new ItemStack(loot.getMaterial()));
                    }
                }
            }
        }
    }

    private static final Map<EntityType, Integer> mobWaveUnlock = Map.ofEntries(
    	    Map.entry(EntityType.ZOMBIE, 1),
    	    Map.entry(EntityType.SKELETON, 1),
    	    Map.entry(EntityType.SPIDER, 1),
    	    Map.entry(EntityType.CREEPER, 2),
    	    Map.entry(EntityType.WOLF, 3),
    	    Map.entry(EntityType.IRON_GOLEM, 5),
    	    Map.entry(EntityType.BLAZE, 7),
    	    Map.entry(EntityType.WITHER_SKELETON, 10),
    	    Map.entry(EntityType.ENDERMAN, 12)
    	);

    public void spawnWave(Arena arena) {
        int wave = arena.getCurrentWave();

        int baseMobs = 5;
        int baseMobsPerPlayer = 2;

        int mobsPerPlayerThisWave = baseMobsPerPlayer + (wave - 1);
        int totalMobs = baseMobs + (mobsPerPlayerThisWave * arena.getPlayers().size());

        List<EntityType> rawMobTypes = arena.getMobTypes();
        if (rawMobTypes.isEmpty()) {
            Bukkit.getLogger().warning("Arena '" + arena.getName() + "' has no mobTypes configured. Using default list.");
            rawMobTypes = List.of(
                EntityType.ZOMBIE,
                EntityType.SKELETON,
                EntityType.SPIDER,
                EntityType.WOLF,
                EntityType.IRON_GOLEM,
                EntityType.CREEPER,
                EntityType.BLAZE,
                EntityType.WITHER_SKELETON
            );
        }

        // Filter per wave unlocks
        List<EntityType> mobsThisWave = rawMobTypes.stream()
            .filter(type -> wave >= mobWaveUnlock.getOrDefault(type, 1))
            .toList();

        if (mobsThisWave.isEmpty()) {
            Bukkit.getLogger().warning("No mobs eligible to spawn in arena '" + arena.getName() + "' for wave " + wave);
            return;
        }

        for (int i = 0; i < totalMobs; i++) {
            Location loc = pickRandomSpawn(arena);
            EntityType mobType = mobsThisWave.get((int)(Math.random() * mobsThisWave.size()));

            var entity = loc.getWorld().spawnEntity(loc, mobType);

            if (entity instanceof LivingEntity living) {
                // Health scaling: +20% per 5 waves
                double baseHealth = living.getAttribute(Attribute.MAX_HEALTH).getBaseValue();
                int increments = (wave - 1) / 5;
                double scaledHealth = baseHealth * (1.0 + (increments * 0.2));
                living.getAttribute(Attribute.MAX_HEALTH).setBaseValue(scaledHealth);
                living.setHealth(scaledHealth);

                // Targeting logic
                Player target = pickRandomPlayer(arena);

                if (mobType == EntityType.WOLF) {
                    if (living instanceof Wolf wolf) {
                        wolf.setAngry(true);
                        if (target != null) {
                            wolf.setTarget(target);
                        }
                    }
                } else if (living instanceof Mob mob) {
                    if (target != null) {
                        mob.setTarget(target);
                    }
                }
            }

            arena.getActiveMobs().add(entity.getUniqueId());
        }

        for (Player player : arena.getPlayers()) {
            player.sendMessage("§eWave " + wave + " has begun! §7" + totalMobs + " mobs spawning.");
        }
    }

    private Player pickRandomPlayer(Arena arena) {
        var players = arena.getPlayers();
        if (players.isEmpty()) return null;
        return players.stream().skip((int) (Math.random() * players.size())).findFirst().orElse(null);
    }

    private Location pickRandomSpawn(Arena arena) {
        var list = arena.getSpawnLocations();
        if (list.isEmpty()) {
            throw new IllegalStateException("No spawn locations set for arena " + arena.getName());
        }
        return list.get((int)(Math.random() * list.size()));
    }

}
