package cloud.mineplay.mobarena.mobs;

import org.bukkit.entity.EntityType;

public class MobSpawn {
    private final EntityType type;
    private final int minWave;

    public MobSpawn(EntityType type, int minWave) {
        this.type = type;
        this.minWave = minWave;
    }

    public EntityType getType() {
        return type;
    }

    public int getMinWave() {
        return minWave;
    }
}
