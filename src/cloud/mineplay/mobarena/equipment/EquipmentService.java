package cloud.mineplay.mobarena.equipment;

import cloud.mineplay.mobarena.storage.DatabaseSettings;
import cloud.mineplay.mobarena.messages.ArenaMessages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.CraftingInventory;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

/** Owns durable inventory recovery. Every player operation and callback runs on the server thread. */
public final class EquipmentService implements Listener {
    private static final class Visit {
        SnapshotStore.Entry entry;
        boolean persisted, writing, restoring, restoreRequested;
        Location exit;
        final List<Runnable> completed = new ArrayList<>();
        Visit(SnapshotStore.Entry entry, boolean persisted) { this.entry = entry; this.persisted = persisted; }
    }

    private record Preparation(UUID token, Player player, String choice, ClassKit kit, Runnable ready, Consumer<String> failed) { }

    private final JavaPlugin plugin;
    private final SnapshotStore store;
    private final ExecutorService worker;
    private final Map<UUID, Visit> visits = new HashMap<>();
    private final Map<UUID, Preparation> preparations = new HashMap<>();
    private final Set<UUID> locked = new HashSet<>();
    private final Set<UUID> internalChanges = new HashSet<>();
    private Map<String, ClassKit> kits;
    private String equipmentMode;
    private Function<UUID, CompletableFuture<Long>> lifetimeKillsLookup = id ->
            CompletableFuture.failedFuture(new IllegalStateException("Lifetime kills are not available"));
    private volatile boolean closing;

    public EquipmentService(JavaPlugin plugin) {
        this.plugin = plugin;
        SnapshotStore opening = null;
        try {
            File directory = plugin.getDataFolder();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create plugin data directory");
            File classes = new File(directory, "classes.yml");
            if (!classes.isFile()) plugin.saveResource("classes.yml", false);
            reload();
            // Startup recovery must be known before any player may interact with class items.
            opening = new SnapshotStore(DatabaseSettings.load(plugin.getConfig().getConfigurationSection("storage"), directory.toPath()));
            for (SnapshotStore.Entry entry : opening.outstanding()) {
                visits.put(entry.playerId(), new Visit(entry, true));
                locked.add(entry.playerId());
            }
        } catch (Exception exception) {
            if (opening != null) {
                try { opening.close(); }
                catch (Exception closeFailure) { exception.addSuppressed(closeFailure); }
            }
            throw new IllegalStateException("Equipment recovery could not initialize; refusing unsafe arena startup", exception);
        }
        store = opening;
        worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "SimpleMobArena-equipment");
            thread.setDaemon(true);
            return thread;
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::recover));
    }

    EquipmentService(JavaPlugin plugin, SnapshotStore store, ExecutorService worker,
                     Map<String, ClassKit> kits, String equipmentMode) {
        this.plugin = plugin;
        this.store = store;
        this.worker = worker;
        this.kits = Map.copyOf(kits);
        this.equipmentMode = equipmentMode;
    }

    public void reload() {
        requireMainThread();
        String mode = plugin.getConfig().getString("equipment.mode", "BOTH").toUpperCase(Locale.ROOT);
        if (!Set.of("PERSONAL", "CLASS", "BOTH").contains(mode)) throw new IllegalArgumentException("equipment.mode must be PERSONAL, CLASS or BOTH");
        Map<String, ClassKit> replacement = ClassKit.load(new File(plugin.getDataFolder(), "classes.yml"));
        kits = replacement;
        equipmentMode = mode;
        for (Preparation preparation : List.copyOf(preparations.values())) {
            UUID id = preparation.player().getUniqueId();
            if (preparations.remove(id, preparation)) {
                locked.remove(id);
                preparation.failed().accept("Classes have changed. Please choose your class again.");
            }
        }
    }

    public void reloadClasses() { reload(); }
    /** The supplied function must queue its lookup and return without waiting for storage. */
    public void setLifetimeKillsLookup(Function<UUID, CompletableFuture<Long>> lookup) {
        requireMainThread();
        lifetimeKillsLookup = java.util.Objects.requireNonNull(lookup);
    }
    public List<String> classNames() { return kits.keySet().stream().sorted().toList(); }
    public List<ClassChoice> classChoices() {
        if (equipmentMode.equals("PERSONAL")) return List.of();
        return kits.values().stream().map(ClassKit::choice)
                .sorted(Comparator.comparingLong(ClassChoice::requiredKills).thenComparing(ClassChoice::id)).toList();
    }
    public ClassChoice choice(String selection) {
        String normalized = normalizeChoice(selection);
        ClassKit kit = equipmentMode.equals("PERSONAL") ? null : kits.get(normalized);
        return kit == null ? null : kit.choice();
    }
    public List<String> availableChoices() {
        List<String> choices = new ArrayList<>();
        if (!equipmentMode.equals("CLASS")) choices.add("PERSONAL");
        if (!equipmentMode.equals("PERSONAL")) choices.addAll(classNames());
        return List.copyOf(choices);
    }
    public boolean preserveDurability() { return plugin.getConfig().getBoolean("equipment.personal.preserve-durability", false); }
    public boolean isLocked(UUID id) { return locked.contains(id); }
    public boolean hasSnapshot(UUID id) { return visits.containsKey(id); }
    public boolean isClass(UUID id) {
        Visit visit = visits.get(id);
        return visit != null && !visit.entry.choice().equals("PERSONAL");
    }

    public void prepare(Player player, String selection, Runnable ready, Consumer<String> failed) {
        requireMainThread();
        UUID id = player.getUniqueId();
        if (closing || visits.containsKey(id) || locked.contains(id) || preparations.containsKey(id)) {
            failed.accept("Your previous equipment recovery is still pending.");
            return;
        }
        String choice = normalizeChoice(selection);
        if (!availableChoices().contains(choice)) {
            failed.accept("Equipment choice is unavailable. Choose: " + String.join(", ", availableChoices()));
            return;
        }
        ClassKit kit = kits.get(choice);
        if (kit != null && kit.choice().requiredKills() > 0) {
            Preparation preparation = new Preparation(UUID.randomUUID(), player, choice, kit, ready, failed);
            preparations.put(id, preparation);
            // Keep the inventory stable while the ledger lookup runs. No snapshot or kit
            // change happens until the result has been checked on the server thread.
            locked.add(id);
            try {
                CompletableFuture<Long> kills = lifetimeKillsLookup.apply(id);
                if (kills == null) throw new IllegalStateException("No lifetime kill lookup result");
                kills.copy().orTimeout(10, TimeUnit.SECONDS).whenComplete((total, error) ->
                        sync(() -> finishUnlockCheck(id, preparation, total, error)));
            } catch (RuntimeException error) {
                finishUnlockCheck(id, preparation, null, error);
            }
            return;
        }
        prepareEquipment(player, choice, kit, ready, failed);
    }

    private static String normalizeChoice(String selection) {
        String choice = selection == null ? "PERSONAL" : selection.trim();
        if (choice.regionMatches(true, 0, "CLASS:", 0, 6)) choice = choice.substring(6).trim();
        return choice.equalsIgnoreCase("PERSONAL") ? "PERSONAL" : choice.toLowerCase(Locale.ROOT);
    }

    private void finishUnlockCheck(UUID id, Preparation preparation, Long kills, Throwable error) {
        requireMainThread();
        if (!preparations.remove(id, preparation)) return;
        locked.remove(id);
        Player player = preparation.player();
        if (closing || !player.isOnline() || Bukkit.getPlayer(id) != player) {
            preparation.failed().accept("You left before your class was ready. Join again to play.");
            return;
        }
        if (error != null || kills == null || kills < 0) {
            preparation.failed().accept("Couldn't check your arena kills. Please try joining again in a moment.");
            return;
        }
        ClassChoice choice = preparation.kit().choice();
        if (!choice.unlocked(kills)) {
            preparation.failed().accept(String.format(Locale.ROOT, "%s unlocks at %,d arena kills. You have %,d.",
                    choice.displayName(), choice.requiredKills(), kills));
            return;
        }
        prepareEquipment(player, preparation.choice(), preparation.kit(), preparation.ready(), preparation.failed());
    }

    /** Cancels a pending unlock lookup without touching an equipment recovery already in progress. */
    public void cancelPreparation(UUID id) {
        requireMainThread();
        if (preparations.remove(id) != null && !visits.containsKey(id)) locked.remove(id);
    }

    private void prepareEquipment(Player player, String choice, ClassKit kit, Runnable ready, Consumer<String> failed) {
        UUID id = player.getUniqueId();
        if (player.isDead() || player.isInsideVehicle() || player.isGliding() || player.isSleeping()) {
            failed.accept("Stand safely before joining.");
            return;
        }
        String carriedError = carriedInventoryError(player);
        if (carriedError != null) {
            failed.accept(carriedError);
            return;
        }
        player.closeInventory();
        locked.add(id);
        final Visit visit;
        try {
            byte[] original = PlayerSnapshot.capture(player,
                    plugin.getConfig().getBoolean("equipment.personal.restore-consumables", false)).encode();
            visit = new Visit(new SnapshotStore.Entry(id, UUID.randomUUID(), choice, original, null,
                    SnapshotStore.Status.PREPARED), false);
            visit.writing = true;
            visits.put(id, visit);
        } catch (Exception exception) {
            locked.remove(id);
            failed.accept("Could not capture your equipment safely. Your inventory has not been changed.");
            plugin.getLogger().log(Level.SEVERE, "Equipment capture failed for " + id, exception);
            return;
        }
        worker.execute(() -> {
            try {
                store.prepare(visit.entry);
                sync(() -> {
                    if (visits.get(id) != visit) return;
                    visit.persisted = true;
                    visit.writing = false;
                    if (!player.isOnline() || Bukkit.getPlayer(id) != player || visit.restoreRequested) {
                        failed.accept("Arena joining was cancelled; your equipment is being recovered.");
                        Player current = Bukkit.getPlayer(id);
                        restore(current == null ? player : current, visit.exit, () -> { });
                        return;
                    }
                    try {
                        internalChanges.add(id);
                        if (kit != null) kit.apply(player);
                        internalChanges.remove(id);
                        locked.remove(id);
                        ready.run();
                    } catch (Exception exception) {
                        internalChanges.remove(id);
                        locked.add(id);
                        failed.accept("Could not equip the arena kit; restoring your saved equipment.");
                        plugin.getLogger().log(Level.SEVERE, "Arena equipment apply failed for " + id, exception);
                        restore(player, null, () -> { });
                    }
                });
            } catch (Exception exception) {
                // A write error may mean a commit succeeded but its acknowledgement failed.
                // Leave the reservation quarantined; never overwrite a possibly durable original.
                sync(() -> {
                    visit.writing = false;
                    failed.accept("Equipment storage failed. Reconnect after an administrator checks recovery.");
                    quarantine(player, "Snapshot storage failed", exception);
                });
            }
        });
    }

    public void restore(Player player, Location exit, Runnable done) {
        requireMainThread();
        UUID id = player.getUniqueId();
        cancelPreparation(id);
        Visit visit = visits.get(id);
        if (visit == null) { done.run(); return; }
        locked.add(id);
        visit.restoreRequested = true;
        if (exit != null) visit.exit = exit.clone();
        visit.completed.add(done);
        if (closing || visit.writing || visit.restoring || !visit.persisted || !player.isOnline() || player.isDead()) return;
        visit.restoring = true;
        try {
            final byte[] target;
            if (visit.entry.status() == SnapshotStore.Status.RESTORING) target = visit.entry.target();
            else target = PlayerSnapshot.decode(visit.entry.original())
                    .restorationTarget(player, isClass(id), visit.exit).encode();
            worker.execute(() -> {
                try {
                    SnapshotStore.Entry restoring = store.beginRestore(id, visit.entry.token(), target);
                    sync(() -> {
                        if (visits.get(id) != visit) return;
                        visit.entry = restoring;
                        Player current = Bukkit.getPlayer(id);
                        if (current == null || !current.isOnline() || current.isDead()) { visit.restoring = false; return; }
                        applyAndConfirm(current, visit);
                    });
                } catch (Exception exception) {
                    sync(() -> { visit.restoring = false; quarantine(player, "Could not persist equipment restore target", exception); });
                }
            });
        } catch (Exception exception) {
            visit.restoring = false;
            quarantine(player, "Could not build equipment restore target", exception);
        }
    }

    private void applyAndConfirm(Player player, Visit visit) {
        UUID id = player.getUniqueId();
        try {
            internalChanges.add(id);
            PlayerSnapshot.decode(visit.entry.target()).apply(player);
            // saveData precedes SQLite confirmation. The player cannot transfer items in this gap.
            player.saveData();
            internalChanges.remove(id);
            worker.execute(() -> {
                try {
                    store.confirm(id, visit.entry.token());
                    sync(() -> complete(id, visit));
                } catch (Exception exception) {
                    sync(() -> { visit.restoring = false; quarantine(player, "Equipment restore confirmation failed", exception); });
                }
            });
        } catch (Exception exception) {
            internalChanges.remove(id);
            visit.restoring = false;
            quarantine(player, "Equipment replacement or player save failed", exception);
        }
    }

    private void complete(UUID id, Visit visit) {
        if (visits.get(id) != visit) return;
        visits.remove(id);
        locked.remove(id);
        for (Runnable callback : List.copyOf(visit.completed)) {
            try { callback.run(); }
            catch (Exception exception) { plugin.getLogger().log(Level.SEVERE, "Equipment completion callback failed", exception); }
        }
        visit.completed.clear();
    }

    public void recover(Player player) {
        requireMainThread();
        Visit visit = visits.get(player.getUniqueId());
        if (visit == null) return;
        // Do not interpret a second recovery hook during an active visit as a leave.
        if (!locked.contains(player.getUniqueId()) && !visit.restoreRequested) return;
        locked.add(player.getUniqueId());
        if (visit.writing || visit.restoring) return;
        player.sendMessage(ArenaMessages.info("Restoring your saved equipment. Please wait."));
        // Re-read after an ambiguous I/O failure: the commit may have succeeded even
        // though its completion did not reach the previous online Player instance.
        visit.writing = true;
        worker.execute(() -> {
            try {
                SnapshotStore.Entry durable = store.get(player.getUniqueId());
                sync(() -> {
                    if (visits.get(player.getUniqueId()) != visit) return;
                    visit.writing = false;
                    if (durable == null || durable.status() == SnapshotStore.Status.CONFIRMED) {
                        // A missing row is safe only before a kit was ever applied.
                        if (durable == null && visit.persisted) {
                            quarantine(player, "Durable original is unexpectedly missing", new IllegalStateException("Snapshot row missing"));
                        } else complete(player.getUniqueId(), visit);
                        return;
                    }
                    visit.entry = durable;
                    visit.persisted = true;
                    Player current = Bukkit.getPlayer(player.getUniqueId());
                    if (current == null || !current.isOnline()) return;
                    restore(current, null, () -> {
                        if (current.isOnline()) current.sendMessage(ArenaMessages.success("Your equipment has been restored."));
                    });
                });
            } catch (Exception exception) {
                sync(() -> { visit.writing = false; quarantine(player, "Recovery record could not be read", exception); });
            }
        });
    }

    /** Restore online players before listeners disappear; offline snapshots remain recoverable. */
    public void shutdown() {
        requireMainThread();
        closing = true;
        for (UUID id : List.copyOf(preparations.keySet())) cancelPreparation(id);
        locked.addAll(visits.keySet());
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("Equipment worker did not stop; snapshots retained for restart recovery.");
                for (UUID id : visits.keySet()) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null) player.kickPlayer("Arena equipment recovery is pending. Please reconnect after restart.");
                }
                return;
            }
            // Re-read disk, since worker completions can be waiting for a main-thread callback.
            for (SnapshotStore.Entry entry : store.outstanding()) {
                Player player = Bukkit.getPlayer(entry.playerId());
                if (player == null || !player.isOnline()) continue;
                if (player.isDead()) {
                    player.kickPlayer("Arena equipment recovery is pending. Please reconnect after restart.");
                    continue;
                }
                try {
                    SnapshotStore.Entry restoring = entry;
                    if (entry.status() == SnapshotStore.Status.PREPARED) {
                        Visit visit = visits.get(entry.playerId());
                        Location exit = visit == null ? null : visit.exit;
                        byte[] target = PlayerSnapshot.decode(entry.original())
                                .restorationTarget(player, !entry.choice().equals("PERSONAL"), exit).encode();
                        restoring = store.beginRestore(entry.playerId(), entry.token(), target);
                    }
                    internalChanges.add(entry.playerId());
                    PlayerSnapshot.decode(restoring.target()).apply(player);
                    player.saveData();
                    internalChanges.remove(entry.playerId());
                    store.confirm(entry.playerId(), entry.token());
                } catch (Exception exception) {
                    internalChanges.remove(entry.playerId());
                    plugin.getLogger().log(Level.SEVERE, "Shutdown recovery pending for " + entry.playerId(), exception);
                    player.kickPlayer("Your saved arena equipment needs recovery. Please reconnect after restart.");
                }
            }
            store.close();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Equipment shutdown failed; retain equipment.db for recovery", exception);
            for (UUID id : visits.keySet()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) player.kickPlayer("Arena equipment recovery is pending. Please reconnect after restart.");
            }
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private void quarantine(Player player, String reason, Exception exception) {
        locked.add(player.getUniqueId());
        plugin.getLogger().log(Level.SEVERE, reason + " for " + player.getUniqueId() + "; original snapshot retained", exception);
        if (player.isOnline()) player.sendMessage(ArenaMessages.error("Equipment recovery needs an administrator. Your saved items are retained; interaction is paused."));
    }
    static String carriedInventoryError(Player player) {
        if (!PlayerSnapshot.empty(player.getItemOnCursor())) return "Put the item on your cursor away before joining.";
        if (player.getOpenInventory().getTopInventory() instanceof CraftingInventory crafting) {
            for (var item : crafting.getMatrix()) {
                if (!PlayerSnapshot.empty(item)) return "Empty your crafting grid before joining so closing it cannot drop items.";
            }
        }
        return null;
    }
    private void sync(Runnable action) {
        if (!closing && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> { if (!closing) action.run(); });
    }
    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Equipment player operations require the server thread");
    }
    private boolean locked(Player player) { return locked.contains(player.getUniqueId()); }
    private boolean externalLocked(Player player) { return locked(player) && !internalChanges.contains(player.getUniqueId()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent event) { recover(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { cancelPreparation(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent event) { Bukkit.getScheduler().runTask(plugin, () -> recover(event.getPlayer())); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) { if (event.getWhoClicked() instanceof Player player && locked(player)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) { if (event.getWhoClicked() instanceof Player player && locked(player)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void open(InventoryOpenEvent event) { if (event.getPlayer() instanceof Player player && locked(player)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) { if (event.getEntity() instanceof Player player && locked(player)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (externalLocked(event.getPlayer()) && event.getTo() != null
                && (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ())) event.setTo(event.getFrom());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { if (externalLocked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) { if (event.getEntity() instanceof Player player && locked(player)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && locked(player)) event.setCancelled(true);
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player && locked(player)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interactAtEntity(PlayerInteractAtEntityEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void armorStand(PlayerArmorStandManipulateEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mount(EntityMountEvent event) { if (event.getEntity() instanceof Player player && locked(player)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucket(PlayerBucketEmptyEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucket(PlayerBucketFillEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        if (!locked(event.getPlayer())) return;
        if (preparations.containsKey(event.getPlayer().getUniqueId())) {
            String[] parts = event.getMessage().replaceFirst("^/", "").strip().toLowerCase(Locale.ROOT).split("\\s+");
            if (parts.length == 2 && parts[1].equals("leave")) {
                String root = parts[0];
                if (root.startsWith("simplemobarena:")) root = root.substring("simplemobarena:".length());
                org.bukkit.command.PluginCommand command = Bukkit.getPluginCommand(parts[0]);
                if ((root.equals("ma") || root.equals("mobarena")) && command != null && command.getPlugin() == plugin) return;
            }
        }
        event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void wear(PlayerItemDamageEvent event) {
        if (locked(event.getPlayer()) || (hasSnapshot(event.getPlayer().getUniqueId())
                && !isClass(event.getPlayer().getUniqueId()) && preserveDurability())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void shoot(EntityShootBowEvent event) { if (event.getEntity() instanceof Player player && locked(player)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void projectile(ProjectileLaunchEvent event) { if (event.getEntity().getShooter() instanceof Player player && locked(player)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void blockBreak(BlockBreakEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void blockPlace(BlockPlaceEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hunger(FoodLevelChangeEvent event) { if (event.getEntity() instanceof Player player && externalLocked(player)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void effect(EntityPotionEffectEvent event) { if (event.getEntity() instanceof Player player && externalLocked(player)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void gameMode(PlayerGameModeChangeEvent event) { if (externalLocked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void experience(PlayerExpChangeEvent event) { if (locked(event.getPlayer())) event.setAmount(0); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mend(PlayerItemMendEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fish(PlayerFishEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void death(PlayerDeathEvent event) {
        if (locked(event.getEntity())) {
            event.getDrops().clear(); event.setDroppedExp(0); event.setKeepInventory(true); event.setKeepLevel(true);
        }
    }
}
