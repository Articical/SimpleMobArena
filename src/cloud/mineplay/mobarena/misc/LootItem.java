package cloud.mineplay.mobarena.misc;

import org.bukkit.Material;

public class LootItem {
	private final Material material;
    private final int weight;
    private final int minWave;

    public LootItem(Material material, int weight, int minWave) {
        this.material = material;
        this.weight = weight;
        this.minWave = minWave;
    }

    public Material getMaterial() {
        return material;
    }

    public int getWeight() {
        return weight;
    }

    public int getMinWave() {
        return minWave;
    }
}
