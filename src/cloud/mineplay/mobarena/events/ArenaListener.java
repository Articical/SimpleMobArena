package cloud.mineplay.mobarena.events;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.*;
import cloud.mineplay.mobarena.enums.GameState;
import cloud.mineplay.mobarena.gui.ArenaMenu;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import net.kyori.adventure.text.Component;

/** One registered listener; all progression and cleanup belongs to Arena. */
public final class ArenaListener implements Listener {
    private final SimpleMobArena plugin;
    public ArenaListener(SimpleMobArena plugin) { this.plugin = plugin; }
    private ArenaManager manager() { return plugin.getArenaManager(); }
    private Arena participant(Entity e) { return e instanceof Player ? manager().getPlayerArena(e.getUniqueId()) : null; }
    private Entity source(Entity e) {
        if (e instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) return shooter;
        if (e instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Entity shooter) return shooter;
        if (e instanceof TNTPrimed tnt && tnt.getSource() != null) return tnt.getSource();
        return e;
    }
    private boolean insideOccupied(Location location) { return manager().occupied(manager().at(location)); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageEvent event) {
        Entity victim = event.getEntity(); Arena victimArena = participant(victim), mobArena = manager().owner(victim);
        if (event instanceof EntityDamageByEntityEvent hit) {
            Entity attacker = source(hit.getDamager()); Arena attackerArena = participant(attacker), attackerMob = manager().owner(attacker);
            Arena projectileArena = manager().owner(hit.getDamager());
            if (victimArena != null) {
                // Only registered session mobs may damage participants. PvP/pets/outsider shots are blocked.
                if (attackerMob != victimArena || attacker instanceof Player || !victimArena.getActiveMobs().contains(attacker.getUniqueId())) event.setCancelled(true);
            } else if (attackerArena != null || attackerMob != null || projectileArena != null) {
                if (mobArena == null || (attackerArena != mobArena && attackerMob != mobArena)) event.setCancelled(true);
            }
            if (mobArena != null && attackerArena != mobArena && attackerMob != mobArena) event.setCancelled(true);
            // The shooter's membership may already be gone while their tagged arrow is in flight.
            if (projectileArena != null && attacker instanceof Player && attackerArena != projectileArena) event.setCancelled(true);
            if (attackerArena != null && (!attackerArena.isParticipant(attacker.getUniqueId()) || !attackerArena.contains(attacker.getLocation()))) event.setCancelled(true);
        }
        if (victimArena != null && !event.isCancelled()) {
            if (!victimArena.isParticipant(victim.getUniqueId()) || (victimArena.getArenaState()!=GameState.ACTIVE && victimArena.getArenaState()!=GameState.INTERMISSION)) {
                event.setCancelled(true); return;
            }
            Player p = (Player)victim;
            if (event.getFinalDamage() >= p.getHealth()) {
                event.setCancelled(true); victimArena.eliminate(p,"was eliminated");
            }
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void damageStatistic(EntityDamageByEntityEvent event) {
        Arena arena = manager().owner(event.getEntity()); Entity actor = source(event.getDamager());
        if (arena != null && actor instanceof Player player && arena.getActiveMobs().contains(event.getEntity().getUniqueId()))
            arena.damage(player.getUniqueId(), Math.min(event.getFinalDamage(),((LivingEntity)event.getEntity()).getHealth()));
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void death(EntityDeathEvent event) {
        Arena arena = manager().owner(event.getEntity());
        if (arena != null || event.getEntity().getPersistentDataContainer().has(plugin.sessionKey(),PersistentDataType.STRING)) {
            event.getDrops().clear(); event.setDroppedExp(0);
            if (arena != null) arena.mobDeath(event.getEntity(),event.getEntity().getKiller());
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void unexpectedDeath(PlayerDeathEvent event) {
        Arena a = participant(event.getEntity());
        if (a != null || plugin.getEquipmentService().hasSnapshot(event.getEntity().getUniqueId())) {
            event.getDrops().clear(); event.setDroppedExp(0); event.setKeepInventory(true); event.setKeepLevel(true);
            // Defensive fallback for another plugin forcing death; ordinary lethal damage is intercepted above.
            if (a != null) a.eliminate(event.getEntity(),"was eliminated");
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void quit(PlayerQuitEvent e) { manager().disconnected(e.getPlayer()); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin,() -> {
            if (!e.getPlayer().isOnline()) return;
            Arena a=manager().at(e.getPlayer().getLocation()); if(manager().occupied(a) && participant(e.getPlayer())!=a) manager().eject(e.getPlayer(),a);
        },2);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void move(PlayerMoveEvent e) {
        if (e.getTo()==null) return;
        Arena a=participant(e.getPlayer());
        if (a!=null && a.isParticipant(e.getPlayer().getUniqueId()) && !a.contains(e.getTo())) {
            e.setCancelled(true); a.eliminate(e.getPlayer(),"left the arena boundary"); return;
        }
        Arena destination=manager().at(e.getTo());
        if (manager().occupied(destination) && a!=destination) { e.setCancelled(true); manager().eject(e.getPlayer(),destination); }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent e) {
        Arena a=participant(e.getPlayer());
        if (a!=null && a.isParticipant(e.getPlayer().getUniqueId()) && !a.contains(e.getTo())) {
            e.setCancelled(true); e.getPlayer().sendMessage(Component.text("[MobArena] Use /ma leave to leave the match.")); return;
        }
        Arena destination=manager().at(e.getTo());
        if (manager().occupied(destination) && a!=destination) {
            e.setCancelled(true); Bukkit.getScheduler().runTask(plugin,() -> { if(e.getPlayer().isOnline()) manager().eject(e.getPlayer(),destination); });
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void commands(PlayerCommandPreprocessEvent e) {
        if (participant(e.getPlayer())==null) return;
        String[] parts=e.getMessage().replaceFirst("^/", "").strip().toLowerCase(Locale.ROOT).split("\\s+");
        org.bukkit.command.PluginCommand resolved = Bukkit.getPluginCommand(parts[0]);
        String root=parts[0];
        if (root.startsWith("simplemobarena:")) root=root.substring("simplemobarena:".length());
        boolean allowed=resolved!=null && resolved.getPlugin()==plugin && (root.equals("ma") || root.equals("mobarena"))
            && (parts.length==1 || Set.of("leave","info","stats","list","history","match","top").contains(parts[1]));
        if (!allowed) { e.setCancelled(true); e.getPlayer().sendMessage(Component.text("[MobArena] Only match commands are available. Use /ma leave to exit.")); }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void target(EntityTargetLivingEntityEvent e) {
        Arena a=manager().owner(e.getEntity());
        if (a!=null && e.getTarget()!=null && (!(e.getTarget() instanceof Player p) || !a.isParticipant(p.getUniqueId()) || !a.contains(p.getLocation()))) e.setCancelled(true);
        if (a==null && e.getTarget() instanceof Player p && participant(p)!=null) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void projectile(ProjectileLaunchEvent e) {
        ProjectileSource shooter=e.getEntity().getShooter();
        if (shooter instanceof Entity entity) {
            Arena a=participant(entity); if(a==null) a=manager().owner(entity);
            if (a!=null && entity instanceof Player && e.getEntity() instanceof Trident) { e.setCancelled(true); return; }
            if(a!=null) { a.tag(e.getEntity()); if(e.getEntity() instanceof AbstractArrow arrow) arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED); }
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void splash(PotionSplashEvent e) {
        Arena a=manager().owner(e.getPotion());
        for (LivingEntity target : e.getAffectedEntities()) {
            Arena targetArena=participant(target); if(targetArena==null) targetArena=manager().owner(target);
            if ((a!=null || targetArena!=null) && a!=targetArena) e.setIntensity(target,0);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void linger(LingeringPotionSplashEvent e) {
        Arena a=manager().owner(e.getEntity()); if(a!=null) { e.setCancelled(true); e.getAreaEffectCloud().remove(); }
        else if (insideOccupied(e.getEntity().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void cloud(AreaEffectCloudApplyEvent e) {
        Arena a=manager().owner(e.getEntity());
        e.getAffectedEntities().removeIf(target -> { Arena t=participant(target); if(t==null)t=manager().owner(target); return (a!=null || t!=null)&&a!=t; });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void explode(EntityExplodeEvent e) {
        if(manager().owner(e.getEntity())!=null) { e.blockList().clear(); e.setYield(0); }
        else e.blockList().removeIf(block -> insideOccupied(block.getLocation()));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void exploded(EntityExplodeEvent e) {
        // A creeper normally discards itself without EntityDeathEvent. Its natural
        // explosion completes that registered mob without awarding a player kill.
        Arena arena = manager().owner(e.getEntity());
        if (arena != null && e.getEntity() instanceof Creeper creeper
                && arena.getActiveMobs().contains(creeper.getUniqueId())) arena.mobDeath(creeper,null);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void explode(BlockExplodeEvent e) { e.blockList().removeIf(block -> insideOccupied(block.getLocation())); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void change(EntityChangeBlockEvent e) { if(manager().owner(e.getEntity())!=null || insideOccupied(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void entityTeleport(EntityTeleportEvent e) { Arena a=manager().owner(e.getEntity()); if(a!=null && !a.contains(e.getTo())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void transform(EntityTransformEvent e) { if(manager().owner(e.getEntity())!=null) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void spawn(CreatureSpawnEvent e) {
        if(insideOccupied(e.getLocation()) && !e.getEntity().getPersistentDataContainer().has(plugin.sessionKey(),PersistentDataType.STRING)) e.setCancelled(true);
    }
    @EventHandler public void loaded(EntitiesLoadEvent e) {
        for(Entity entity:e.getEntities()) if(entity.getPersistentDataContainer().has(plugin.sessionKey(),PersistentDataType.STRING) && manager().owner(entity)==null) entity.remove();
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void drop(PlayerDropItemEvent e) { if(participant(e.getPlayer())!=null) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void pickup(EntityPickupItemEvent e) { if(participant(e.getEntity())!=null || manager().owner(e.getEntity())!=null) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void entityDrop(EntityDropItemEvent e) { if(manager().owner(e.getEntity())!=null) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void breakBlock(BlockBreakEvent e) { if(participant(e.getPlayer())!=null || insideOccupied(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void placeBlock(BlockPlaceEvent e) { if(participant(e.getPlayer())!=null || insideOccupied(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void bucket(PlayerBucketEmptyEvent e) { if(participant(e.getPlayer())!=null || insideOccupied(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void bucket(PlayerBucketFillEvent e) { if(participant(e.getPlayer())!=null || insideOccupied(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void inventory(InventoryOpenEvent e) {
        if(participant(e.getPlayer())!=null && !ArenaMenu.isMenu(e.getInventory()) && e.getInventory().getType()!=InventoryType.CRAFTING) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void click(InventoryClickEvent e) {
        if(participant(e.getWhoClicked())!=null && (e.getAction()==InventoryAction.DROP_ALL_CURSOR || e.getAction()==InventoryAction.DROP_ALL_SLOT
            || e.getAction()==InventoryAction.DROP_ONE_CURSOR || e.getAction()==InventoryAction.DROP_ONE_SLOT
            || bundle(e.getCurrentItem()) || bundle(e.getCursor())
            || (e.getView().getTopInventory().getType()==InventoryType.CRAFTING && e.getRawSlot()>=0 && e.getRawSlot()<=4))) e.setCancelled(true);
    }
    private static boolean bundle(ItemStack item) { return item!=null && item.getItemMeta() instanceof BundleMeta; }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void drag(InventoryDragEvent e) { if(participant(e.getWhoClicked())!=null && e.getRawSlots().stream().anyMatch(slot -> slot<=4)) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interactEntity(PlayerInteractEntityEvent e) { if(participant(e.getPlayer())!=null || manager().owner(e.getRightClicked())!=null) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interactAtEntity(PlayerInteractAtEntityEvent e) { interactEntity(e); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void armorStand(PlayerArmorStandManipulateEvent e) { interactEntity(e); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void mount(EntityMountEvent e) { if(participant(e.getEntity())!=null || (e.getEntity() instanceof Player && manager().owner(e.getMount())!=null)) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void fish(PlayerFishEvent e) { if(participant(e.getPlayer())!=null) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent e) {
        if(participant(e.getPlayer())==null) return;
        if(e.getClickedBlock()!=null) e.setUseInteractedBlock(Event.Result.DENY);
        Material item=e.getMaterial();
        if(item.isBlock() || item.name().endsWith("BUCKET") || Set.of(Material.ENDER_PEARL,Material.CHORUS_FRUIT,Material.FIRE_CHARGE,
            Material.FLINT_AND_STEEL,Material.FIREWORK_ROCKET,Material.ARMOR_STAND,Material.END_CRYSTAL,Material.EGG,
            Material.EXPERIENCE_BOTTLE,Material.BONE_MEAL,Material.TRIDENT,Material.FISHING_ROD,Material.WIND_CHARGE).contains(item)
            || item.name().endsWith("BUNDLE") || item.name().contains("BOAT") || item.name().contains("MINECART"))
            e.setUseItemInHand(Event.Result.DENY);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void experience(PlayerExpChangeEvent e) { if(participant(e.getPlayer())!=null) e.setAmount(0); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void mending(PlayerItemMendEvent e) { if(participant(e.getPlayer())!=null) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void ignite(BlockIgniteEvent e) { if(insideOccupied(e.getBlock().getLocation()) || (e.getIgnitingEntity()!=null && manager().owner(source(e.getIgnitingEntity()))!=null)) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void burn(BlockBurnEvent e) { if(insideOccupied(e.getBlock().getLocation())) e.setCancelled(true); }
}
