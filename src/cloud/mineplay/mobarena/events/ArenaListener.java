package cloud.mineplay.mobarena.events;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.*;
import cloud.mineplay.mobarena.enums.GameState;
import cloud.mineplay.mobarena.gui.ArenaMenu;
import cloud.mineplay.mobarena.messages.ArenaMessages;
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

/** One registered listener; all progression and cleanup belongs to Arena. */
public final class ArenaListener implements Listener {
    private final SimpleMobArena plugin;
    private final DuelPotionAttribution potionAttribution;
    public ArenaListener(SimpleMobArena plugin) { this.plugin = plugin; this.potionAttribution = new DuelPotionAttribution(plugin); }
    public void tick() { potionAttribution.prune(); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void finished(cloud.mineplay.mobarena.cevents.ArenaEndEvent event) { potionAttribution.finished(event.getArena()); }
    private ArenaManager manager() { return plugin.getArenaManager(); }
    private Arena participant(Entity e) { return e instanceof Player ? manager().getPlayerArena(e.getUniqueId()) : null; }
    private Entity source(Entity e) {
        if (e instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) return shooter;
        if (e instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Entity shooter) return shooter;
        if (e instanceof EvokerFangs fangs && fangs.getOwner() != null) return fangs.getOwner();
        if (e instanceof TNTPrimed tnt && tnt.getSource() != null) return tnt.getSource();
        return e;
    }
    private boolean insideOccupied(Location location) { return manager().occupied(manager().at(location)); }
    private boolean activeProjectileShooter(Arena arena, Player player) {
        GameState phase = arena.getArenaState();
        return participant(player) == arena && arena.isParticipant(player.getUniqueId()) && arena.contains(player.getLocation())
                && (phase == GameState.ACTIVE || !arena.isDuel() && phase == GameState.INTERMISSION);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void sunlight(EntityCombustEvent event) {
        // Sun ignition uses the base event. Keep fire/lava contact and attack ignition intact.
        if (event instanceof EntityCombustByBlockEvent || event instanceof EntityCombustByEntityEvent) return;
        Entity entity = event.getEntity();
        if ((entity instanceof Zombie || entity instanceof AbstractSkeleton || entity instanceof Phantom) && manager().owner(entity) != null) {
            event.setCancelled(true);
            entity.setFireTicks(0);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageEvent event) {
        Entity victim = event.getEntity(); Arena victimArena = participant(victim), mobArena = manager().owner(victim);
        Player duelAttacker = null;
        if (event instanceof EntityDamageByEntityEvent hit) {
            Entity attacker = source(hit.getDamager()); Arena attackerArena = participant(attacker), attackerMob = manager().owner(attacker);
            Arena projectileArena = manager().owner(hit.getDamager());
            if (victimArena != null) {
                if (victimArena.isDuel()) {
                    if (attacker instanceof Player player && attackerArena == victimArena
                            && victimArena.opponents(player.getUniqueId(),victim.getUniqueId())
                            && victimArena.contains(victim.getLocation())) duelAttacker = player;
                    else event.setCancelled(true);
                } else if (attackerMob != victimArena || attacker instanceof Player || !victimArena.isCombatMob(attacker.getUniqueId())
                        || !victimArena.contains(victim.getLocation()) || !victimArena.contains(attacker.getLocation())) event.setCancelled(true);
            } else if (attackerArena != null || attackerMob != null || projectileArena != null) {
                if (mobArena == null || (attackerArena != mobArena && attackerMob != mobArena)) event.setCancelled(true);
            }
            if (mobArena != null && attackerArena != mobArena && attackerMob != mobArena) event.setCancelled(true);
            // The shooter's membership may already be gone while their tagged arrow is in flight.
            if (projectileArena != null && attacker instanceof Player && attackerArena != projectileArena) event.setCancelled(true);
            // Untagged shots may have been fired before joining or in another session.
            if (hit.getDamager() instanceof Projectile && attackerArena != null && projectileArena != attackerArena) event.setCancelled(true);
            if (attackerArena != null && (!attackerArena.isParticipant(attacker.getUniqueId()) || !attackerArena.contains(attacker.getLocation()))) event.setCancelled(true);
        } else if (victimArena != null && victimArena.isDuel() && victim instanceof Player player) {
            duelAttacker = potionAttribution.attacker(event,player,victimArena);
        }
        if (victimArena != null && !event.isCancelled()) {
            if (!victimArena.isParticipant(victim.getUniqueId()) || (victimArena.getArenaState()!=GameState.ACTIVE && victimArena.getArenaState()!=GameState.INTERMISSION)) {
                event.setCancelled(true); return;
            }
            Player p = (Player)victim;
            if (event.getFinalDamage() >= p.getHealth()) {
                if (duelAttacker != null) victimArena.duelHit(duelAttacker.getUniqueId(),p.getUniqueId(),p.getHealth());
                event.setCancelled(true); victimArena.eliminate(p,"was eliminated");
            }
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void damageStatistic(EntityDamageByEntityEvent event) {
        Arena arena = manager().owner(event.getEntity()); Entity actor = source(event.getDamager());
        Arena duel = participant(event.getEntity());
        if (duel != null && duel.isDuel() && actor instanceof Player player && event.getEntity() instanceof LivingEntity target)
            duel.duelHit(player.getUniqueId(),target.getUniqueId(),Math.min(event.getFinalDamage(),target.getHealth()));
        if (arena != null && actor instanceof Player player && arena.getActiveMobs().contains(event.getEntity().getUniqueId()))
            arena.damage(player.getUniqueId(), Math.min(event.getFinalDamage(),((LivingEntity)event.getEntity()).getHealth()));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void beeSting(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !(event.getDamager() instanceof Bee bee)
                || !(event.getEntity() instanceof Player victim)) return;
        Arena arena = manager().owner(bee);
        if (arena == null || participant(victim) != arena || !arena.isParticipant(victim.getUniqueId())
                || !arena.isCombatMob(bee.getUniqueId())) return;
        UUID session = arena.getSessionId();
        // Vanilla marks the bee as stung after this damage event returns. Clear it
        // next tick, before its post-sting death check can remove a tracked enemy.
        plugin.getServer().getScheduler().runTask(plugin,() -> {
            if (!bee.isValid() || manager().owner(bee) != arena || !arena.getSessionId().equals(session)
                    || !arena.isCombatMob(bee.getUniqueId())) return;
            bee.setHasStung(false);
            bee.setTimeSinceSting(0);
            bee.setAnger(1200);
        });
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void damageOverTimeStatistic(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent || !(event.getEntity() instanceof Player victim)) return;
        Arena arena = participant(victim);
        if (arena == null || !arena.isDuel()) return;
        Player attacker = potionAttribution.attacker(event,victim,arena);
        if (attacker != null) arena.duelHit(attacker.getUniqueId(),victim.getUniqueId(),Math.min(event.getFinalDamage(),victim.getHealth()));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void appliedSplash(PotionSplashEvent event) { potionAttribution.splash(event); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void potionEffect(EntityPotionEffectEvent event) { potionAttribution.changed(event); }
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
    public void quit(PlayerQuitEvent e) { potionAttribution.forget(e.getPlayer().getUniqueId()); manager().disconnected(e.getPlayer()); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin,() -> {
            if (!e.getPlayer().isOnline()) return;
            Arena a=manager().at(e.getPlayer().getLocation()); if(manager().occupied(a) && participant(e.getPlayer())!=a && !manager().canBypass(e.getPlayer())) manager().eject(e.getPlayer(),a);
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
        if (manager().occupied(destination) && a!=destination && !manager().canBypass(e.getPlayer())) { e.setCancelled(true); manager().eject(e.getPlayer(),destination); }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent e) {
        Arena a=participant(e.getPlayer());
        if (a!=null && a.isParticipant(e.getPlayer().getUniqueId()) && !a.contains(e.getTo())) {
            e.setCancelled(true); e.getPlayer().sendMessage(ArenaMessages.warning("Use /ma leave to leave the match.")); return;
        }
        Arena destination=manager().at(e.getTo());
        if (manager().occupied(destination) && a!=destination && !manager().canBypass(e.getPlayer())) {
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
            && (parts.length==1 || Set.of("leave","info","classes","stats","list","history","duels","match","top").contains(parts[1]));
        if (!allowed) { e.setCancelled(true); e.getPlayer().sendMessage(ArenaMessages.warning("Only match commands are available. Use /ma leave to exit.")); }
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
            if (a!=null && entity instanceof Player player && !activeProjectileShooter(a,player)) { e.setCancelled(true); return; }
            if (a!=null && !(entity instanceof Player) && (!a.isCombatMob(entity.getUniqueId()) || !a.contains(entity.getLocation()))) { e.setCancelled(true); return; }
            if(a!=null) { a.tag(e.getEntity()); if(e.getEntity() instanceof AbstractArrow arrow) arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED); }
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void projectileHit(ProjectileHitEvent e) {
        Arena owner = manager().owner(e.getEntity());
        if (owner != null && !owner.contains(e.getEntity().getLocation())) {
            e.setCancelled(true);
            e.getEntity().remove();
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void splash(PotionSplashEvent e) {
        Arena a=manager().owner(e.getPotion());
        Entity shooter = source(e.getPotion());
        Arena shooterArena = participant(shooter);
        boolean invalidPlayerSource = shooter instanceof Player player && (a != null || shooterArena != null)
            && (a == null || !activeProjectileShooter(a,player));
        boolean invalidMobSource = a != null && !(shooter instanceof Player)
            && (a.isDuel() || manager().owner(shooter) != a || !a.isCombatMob(shooter.getUniqueId()) || !a.contains(shooter.getLocation()));
        boolean harmful = e.getPotion().getEffects().stream().anyMatch(effect ->
            effect.getType().getEffectCategory() == org.bukkit.potion.PotionEffectType.Category.HARMFUL);
        for (LivingEntity target : e.getAffectedEntities()) {
            if (invalidPlayerSource || invalidMobSource) { e.setIntensity(target,0); continue; }
            Arena targetArena=participant(target); if(targetArena==null) targetArena=manager().owner(target);
            if ((a!=null || targetArena!=null) && (a!=targetArena || a==null || !a.contains(target.getLocation()))) {
                e.setIntensity(target,0); continue;
            }
            if (a != null && target instanceof Player recipient && !a.isParticipant(recipient.getUniqueId())) {
                e.setIntensity(target,0); continue;
            }
            if (a != null && shooter instanceof Player player) {
                if (target instanceof Player recipient) {
                    boolean enemy = a.opponents(player.getUniqueId(),recipient.getUniqueId());
                    // Mixed potions are treated as harmful. Helpful splash effects stay with teammates.
                    if (harmful ? !enemy : a.isDuel() && enemy) e.setIntensity(target,0);
                }
            }
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
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void prime(ExplosionPrimeEvent e) {
        if (manager().owner(e.getEntity()) != null) e.setFire(false);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void primed(ExplosionPrimeEvent e) {
        // With mobGriefing=false Paper need not fire EntityExplodeEvent at all.
        // A cancelled prime leaves the creeper alive, so only accepted primes count.
        Arena arena = manager().owner(e.getEntity());
        if (!e.isCancelled() && arena != null && e.getEntity() instanceof Creeper creeper)
            arena.mobExploded(creeper);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void exploded(EntityExplodeEvent e) {
        // Cancelling block destruction still discards a creeper. Finish it after
        // the explosion has dealt damage, including WorldGuard-cancelled explosions.
        Arena arena = manager().owner(e.getEntity());
        if (arena != null && e.getEntity() instanceof Creeper creeper) arena.mobExploded(creeper);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void explode(BlockExplodeEvent e) { e.blockList().removeIf(block -> insideOccupied(block.getLocation())); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void change(EntityChangeBlockEvent e) { if(manager().owner(e.getEntity())!=null || insideOccupied(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void mobInteract(EntityInteractEvent e) { if(manager().owner(e.getEntity())!=null || insideOccupied(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void entityTeleport(EntityTeleportEvent e) { Arena a=manager().owner(e.getEntity()); if(a!=null && !a.contains(e.getTo())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void transform(EntityTransformEvent e) { if(manager().owner(e.getEntity())!=null) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void spawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Mob mob)) return;
        Arena destination = manager().at(e.getLocation());
        if (mob instanceof Vex vex && !e.isCancelled() && vex.getOwner() instanceof Mob summoner) {
            Arena owner = manager().owner(summoner);
            if (owner != null && (destination != owner || !owner.adoptSummonedMob(vex,summoner))) {
                e.setCancelled(true); return;
            }
        }
        // Enabled arenas stay clear between matches; only this arena's current session may spawn here.
        if (destination != null && !destination.owns(e.getEntity())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void spellSpawn(EntitySpawnEvent e) {
        if (e.getEntity() instanceof EvokerFangs fangs && fangs.getOwner() instanceof Mob summoner) {
            Arena owner = manager().owner(summoner);
            if (owner != null) {
                if (!owner.isCombatMob(summoner.getUniqueId()) || !owner.contains(e.getLocation())) e.setCancelled(true);
                else owner.tag(fangs);
            } else if (insideOccupied(e.getLocation())) e.setCancelled(true);
        } else if (e.getEntity() instanceof AreaEffectCloud && insideOccupied(e.getLocation())) {
            // Creepers can leave potion clouds when they explode. They are not wave
            // enemies and must not outlive their source or leak effects into another match.
            e.setCancelled(true);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void pushed(io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent e) {
        Entity attacker = source(e.getPushedBy()), victim = e.getEntity();
        Arena attackArena = manager().owner(attacker);
        if (attackArena == null) attackArena = manager().owner(e.getPushedBy());
        Arena targetArena = participant(victim);
        if (targetArena == null) targetArena = manager().owner(victim);
        if (attacker instanceof Player player) {
            Arena shooterArena = participant(player);
            if (shooterArena == null && attackArena == null && targetArena == null) return;
            if (shooterArena == null || shooterArena != targetArena || !activeProjectileShooter(shooterArena,player)
                    || !shooterArena.contains(victim.getLocation())
                    || e.getPushedBy() instanceof Projectile && attackArena != shooterArena
                    || victim instanceof Player recipient && !shooterArena.opponents(player.getUniqueId(),recipient.getUniqueId())) e.setCancelled(true);
            return;
        }
        if (attackArena != null) {
            if (attackArena != targetArena || !attackArena.isCombatMob(attacker.getUniqueId())
                    || !attackArena.contains(attacker.getLocation()) || !attackArena.contains(victim.getLocation())
                    || victim instanceof Player player && !attackArena.isParticipant(player.getUniqueId())) e.setCancelled(true);
        } else if (targetArena != null && participant(attacker) != targetArena) e.setCancelled(true);
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
    public void breakBlock(BlockBreakEvent e) { if(participant(e.getPlayer())!=null || insideOccupied(e.getBlock().getLocation()) && !manager().canBypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void placeBlock(BlockPlaceEvent e) { if(participant(e.getPlayer())!=null || insideOccupied(e.getBlock().getLocation()) && !manager().canBypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void bucket(PlayerBucketEmptyEvent e) { if(participant(e.getPlayer())!=null || insideOccupied(e.getBlock().getLocation()) && !manager().canBypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void bucket(PlayerBucketFillEvent e) { if(participant(e.getPlayer())!=null || insideOccupied(e.getBlock().getLocation()) && !manager().canBypass(e.getPlayer())) e.setCancelled(true); }
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
