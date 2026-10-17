package cloud.mineplay.mobarena.equipment;

import org.bukkit.Material;

/** Public menu metadata. Unlocks use saved lifetime arena kills. */
public record ClassChoice(String id, String displayName, Material icon, long requiredKills) {
    public boolean unlocked(long lifetimeKills) {
        return lifetimeKills >= 0 && lifetimeKills >= requiredKills;
    }
}
