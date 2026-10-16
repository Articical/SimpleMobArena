package cloud.mineplay.mobarena.gui;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.commands.CommandManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Holder identity and explicit slot actions; titles and item names never authorize actions. */
public final class ArenaMenu implements Listener {
    private final SimpleMobArena plugin;
    public ArenaMenu(SimpleMobArena plugin) { this.plugin = plugin; }
    public static boolean isMenu(Inventory inventory) { return inventory.getHolder() instanceof Menu; }

    public void open(Player player, int requestedPage) {
        if (!player.hasPermission("mobarena.use")) throw new IllegalArgumentException("Missing permission: mobarena.use");
        List<Arena> arenas = plugin.getArenaManager().getArenas().stream().sorted(Comparator.comparing(Arena::getId)).toList();
        int lastPage = Math.max(0, (arenas.size() - 1) / 45);
        int page = Math.max(0, Math.min(requestedPage, lastPage));
        Menu holder = new Menu(player.getUniqueId(), "MobArena - " + (page + 1));
        for (int i = page * 45; i < Math.min(arenas.size(), (page + 1) * 45); i++) {
            Arena arena = arenas.get(i);
            List<String> lore = new ArrayList<>(List.of("State: " + arena.getArenaState(), "Players: " + arena.getPlayers().size() + "/" + arena.getMaxPlayers(), "Wave: " + arena.getCurrentWave() + " | Completed: " + arena.getCompletedWaves(), "Pot: " + arena.getPrizePot().toPlainString(), "Your kills: " + arena.getKills(player.getUniqueId()), "Estimated share: " + CommandManager.estimatedShare(arena, player.getUniqueId()).toPlainString(), "Before eligibility and caps; not guaranteed", "Left-click to select equipment"));
            if (!arena.getValidationErrors().isEmpty()) lore.add("Unavailable: needs administrator setup");
            holder.put(i % 45, item(Material.IRON_SWORD, arena.getName(), lore), new Action(Kind.EQUIPMENT, arena.getId(), "", 0));
        }
        if (page > 0) holder.put(45, item(Material.ARROW, "Previous page", List.of()), new Action(Kind.PAGE, "", "", page - 1));
        holder.put(48, item(Material.BARRIER, "Leave arena", List.of("Earned contribution remains recorded")), new Action(Kind.LEAVE, "", "", 0));
        holder.put(49, item(Material.BOOK, "Your statistics", List.of("Sessions, kills, best wave, confirmed earnings")), new Action(Kind.STATS, "", "", 0));
        holder.put(51, item(Material.WRITABLE_BOOK, "Match and payout history", List.of("Recent matches and confirmed rewards")), new Action(Kind.HISTORY, "", "", 0));
        holder.put(50, item(Material.CLOCK, "Refresh", List.of()), new Action(Kind.PAGE, "", "", page));
        if (page < lastPage) holder.put(53, item(Material.ARROW, "Next page", List.of()), new Action(Kind.PAGE, "", "", page + 1));
        player.openInventory(holder.inventory);
    }

    public void openEquipment(Player player, String arenaId) {
        if (!player.hasPermission("mobarena.join")) throw new IllegalArgumentException("Missing permission: mobarena.join");
        Arena arena = plugin.getArenaManager().getArena(arenaId);
        if (arena == null) throw new IllegalArgumentException("Arena no longer exists.");
        Menu holder = new Menu(player.getUniqueId(), "Equipment - " + arena.getName());
        List<String> choices = plugin.getEquipmentService().availableChoices();
        for (int i = 0; i < Math.min(choices.size(), 45); i++) {
            String choice = choices.get(i);
            boolean personal = choice.equalsIgnoreCase("personal");
            holder.put(i, item(personal ? Material.CHEST : Material.IRON_CHESTPLATE, personal ? "Personal equipment" : choice, List.of(personal ? "Use your survival inventory" : "Your original equipment is saved for restoration", "Left-click to join " + arena.getName())), new Action(Kind.JOIN, arenaId, choice, 0));
        }
        if (choices.size() > 45) holder.put(46, item(Material.PAPER, "More classes available", List.of("Use /ma join " + arenaId + " <class>", "Tab completion lists every class")), null);
        holder.put(49, item(Material.ARROW, "Back to arenas", List.of()), new Action(Kind.PAGE, "", "", 0));
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId()) || holder.closed || event.getClick() != ClickType.LEFT) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.inventory.getSize()) return;
        Action action = holder.actions.get(slot);
        if (action == null) return;
        holder.closed = true;
        // Opening/closing inventories inside InventoryClickEvent is unsafe.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.closeInventory();
            try {
                switch (action.kind) {
                    case PAGE -> open(player, action.page);
                    case EQUIPMENT -> openEquipment(player, action.arena);
                    case JOIN -> {
                        if (!player.hasPermission("mobarena.join")) throw new IllegalArgumentException("Missing permission: mobarena.join");
                        plugin.getArenaManager().join(player, action.arena, action.choice);
                    }
                    case LEAVE -> plugin.getArenaManager().leave(player);
                    case STATS -> player.performCommand("mobarena stats");
                    case HISTORY -> player.performCommand("mobarena history");
                }
            } catch (IllegalArgumentException | IllegalStateException ex) {
                player.sendMessage(Component.text("[MobArena] " + ex.getMessage(), NamedTextColor.RED));
            }
        });
    }

    @EventHandler public void drag(InventoryDragEvent event) { if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true); }
    @EventHandler public void close(InventoryCloseEvent event) { if (event.getInventory().getHolder() instanceof Menu holder) holder.closed = true; }

    private static ItemStack item(Material material, String name, List<String> lines) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD));
        meta.lore(lines.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }
    private enum Kind { PAGE, EQUIPMENT, JOIN, LEAVE, STATS, HISTORY }
    private record Action(Kind kind, String arena, String choice, int page) {}
    private static final class Menu implements InventoryHolder {
        private final UUID owner;
        private final Inventory inventory;
        private final Map<Integer, Action> actions = new HashMap<>();
        private boolean closed;
        private Menu(UUID owner, String title) { this.owner = owner; this.inventory = Bukkit.createInventory(this, 54, Component.text(title)); }
        private void put(int slot, ItemStack item, Action action) { inventory.setItem(slot, item); if (action != null) actions.put(slot, action); }
        @Override public Inventory getInventory() { return inventory; }
    }
}
