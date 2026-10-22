package cloud.mineplay.mobarena.events;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.enums.GameState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Main-thread attribution for damage from effects that were actually applied by a duel splash. */
final class DuelPotionAttribution {
    private static final int MAX_TRACKED_EFFECTS = 4096;
    private record EffectKey(UUID victim, String type) {}
    private record Application(Arena arena, UUID session, UUID attacker, UUID projectile, int amplifier, long tick) {}
    private record Credit(Arena arena, UUID session, UUID attacker, PotionEffectType type, int amplifier, long expiresAt) {}

    private final SimpleMobArena plugin;
    private final Map<EffectKey,Application> applying = new LinkedHashMap<>();
    private final Map<EffectKey,Credit> active = new LinkedHashMap<>();

    DuelPotionAttribution(SimpleMobArena plugin) { this.plugin = plugin; }

    void splash(PotionSplashEvent event) {
        if (event.isCancelled()) return;
        prune();
        ThrownPotion potion = event.getPotion();
        if (!(potion.getShooter() instanceof Player attacker)) return;
        Arena arena = plugin.getArenaManager().owner(potion);
        if (arena == null || !arena.isDuel()) return;
        for (var entity : event.getAffectedEntities()) {
            if (!(entity instanceof Player victim)) continue;
            double intensity = event.getIntensity(victim);
            if (!Double.isFinite(intensity) || intensity <= 0 || !opponents(arena,attacker,victim)) continue;
            for (PotionEffect effect : potion.getEffects()) {
                String type = effect.getType().getKey().toString();
                if (!damaging(type)) continue;
                putBounded(applying,new EffectKey(victim.getUniqueId(),type),new Application(arena,arena.getSessionId(),
                        attacker.getUniqueId(),potion.getUniqueId(),effect.getAmplifier(),plugin.ticks()));
            }
        }
    }

    void changed(EntityPotionEffectEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Player victim)) return;
        String type = event.getModifiedType().getKey().toString();
        if (!damaging(type)) return;
        EffectKey key = new EffectKey(victim.getUniqueId(),type);
        // A weaker effect that is refused does not replace the effect currently doing damage.
        if (event.getAction() == EntityPotionEffectEvent.Action.CHANGED && !event.isOverride()) { applying.remove(key); return; }
        active.remove(key);
        Application application = applying.remove(key);
        if (event.getAction() != EntityPotionEffectEvent.Action.ADDED && event.getAction() != EntityPotionEffectEvent.Action.CHANGED) return;
        PotionEffect effect = event.getNewEffect();
        Entity source = event.getSource();
        if (event.getCause() != EntityPotionEffectEvent.Cause.POTION_SPLASH || application == null || effect == null
                || effect.getDuration() <= 0 || effect.getAmplifier() != application.amplifier()
                || application.tick() != plugin.ticks() || !application.session().equals(application.arena().getSessionId())
                || source == null || !(application.attacker().equals(source.getUniqueId()) || application.projectile().equals(source.getUniqueId()))) return;
        Player attacker = plugin.getServer().getPlayer(application.attacker());
        if (attacker == null || !opponents(application.arena(),attacker,victim)) return;
        putBounded(active,key,new Credit(application.arena(),application.session(),application.attacker(),effect.getType(),
                effect.getAmplifier(),plugin.ticks()+effect.getDuration()));
    }

    Player attacker(EntityDamageEvent event, Player victim, Arena arena) {
        if (event.isCancelled()) return null;
        String type = switch (event.getCause()) {
            case POISON -> "minecraft:poison";
            case WITHER -> "minecraft:wither";
            case null, default -> "";
        };
        if (type.isEmpty()) return null;
        EffectKey key = new EffectKey(victim.getUniqueId(),type);
        Credit credit = active.get(key);
        if (credit == null) return null;
        Player attacker = plugin.getServer().getPlayer(credit.attacker());
        PotionEffect effect = victim.getPotionEffect(credit.type());
        long remaining = credit.expiresAt()-plugin.ticks();
        if (credit.arena() != arena || !credit.session().equals(arena.getSessionId()) || remaining <= 0
                || attacker == null || !opponents(arena,attacker,victim) || effect == null
                || effect.getAmplifier() != credit.amplifier() || effect.getDuration() <= 0 || effect.getDuration() > remaining+1) {
            active.remove(key);
            return null;
        }
        return attacker;
    }

    void forget(UUID player) {
        applying.entrySet().removeIf(entry -> entry.getKey().victim().equals(player) || entry.getValue().attacker().equals(player));
        active.entrySet().removeIf(entry -> entry.getKey().victim().equals(player) || entry.getValue().attacker().equals(player));
    }

    void finished(Arena arena) {
        applying.entrySet().removeIf(entry -> entry.getValue().arena() == arena);
        active.entrySet().removeIf(entry -> entry.getValue().arena() == arena);
    }

    private boolean opponents(Arena arena, Player attacker, Player victim) {
        return arena.isDuel() && arena.getArenaState() == GameState.ACTIVE
                && plugin.getArenaManager().getPlayerArena(attacker.getUniqueId()) == arena
                && plugin.getArenaManager().getPlayerArena(victim.getUniqueId()) == arena
                && arena.isParticipant(attacker.getUniqueId()) && arena.isParticipant(victim.getUniqueId())
                && arena.contains(attacker.getLocation()) && arena.contains(victim.getLocation())
                && arena.opponents(attacker.getUniqueId(),victim.getUniqueId());
    }

    void prune() {
        long tick = plugin.ticks();
        applying.entrySet().removeIf(entry -> entry.getValue().tick() != tick
                || !entry.getValue().session().equals(entry.getValue().arena().getSessionId()));
        active.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= tick
                || !entry.getValue().session().equals(entry.getValue().arena().getSessionId())
                || plugin.getArenaManager().getPlayerArena(entry.getKey().victim()) != entry.getValue().arena()
                || plugin.getArenaManager().getPlayerArena(entry.getValue().attacker()) != entry.getValue().arena()
                || !entry.getValue().arena().isParticipant(entry.getKey().victim())
                || !entry.getValue().arena().isParticipant(entry.getValue().attacker()));
    }

    private static <T> void putBounded(Map<EffectKey,T> map, EffectKey key, T value) {
        map.put(key,value);
        if (map.size() > MAX_TRACKED_EFFECTS) map.remove(map.keySet().iterator().next());
    }

    private static boolean damaging(String type) { return type.equals("minecraft:poison") || type.equals("minecraft:wither"); }
}
