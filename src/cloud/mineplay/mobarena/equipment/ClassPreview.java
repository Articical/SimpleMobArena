package cloud.mineplay.mobarena.equipment;

import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Arrays;
import java.util.List;

/** Display copies of a kit. Opening or modifying a preview cannot change the configured equipment. */
public record ClassPreview(ClassChoice choice, ItemStack[] inventory, ItemStack[] armor, ItemStack offhand,
                           List<PotionEffect> effects) {
    public ClassPreview {
        inventory = copies(inventory);
        armor = copies(armor);
        offhand = copy(offhand);
        effects = List.copyOf(effects);
    }

    @Override public ItemStack[] inventory() { return copies(inventory); }
    @Override public ItemStack[] armor() { return copies(armor); }
    @Override public ItemStack offhand() { return copy(offhand); }

    private static ItemStack copy(ItemStack item) { return item == null ? null : item.clone(); }
    private static ItemStack[] copies(ItemStack[] items) {
        return Arrays.stream(items).map(ClassPreview::copy).toArray(ItemStack[]::new);
    }
}
