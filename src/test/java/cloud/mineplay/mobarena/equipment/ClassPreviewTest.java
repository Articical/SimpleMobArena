package cloud.mineplay.mobarena.equipment;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClassPreviewTest {
    @Test void previewDefensivelyCopiesAllEquipmentOnConstructionAndOnEveryRead() {
        ItemStack original = mock(ItemStack.class), saved = mock(ItemStack.class);
        ItemStack firstRead = mock(ItemStack.class), secondRead = mock(ItemStack.class);
        when(original.clone()).thenReturn(saved);
        when(saved.clone()).thenReturn(firstRead, secondRead);
        ItemStack[] inventory = new ItemStack[36], armor = new ItemStack[4];
        inventory[8] = original; armor[3] = original;
        var effects = new ArrayList<org.bukkit.potion.PotionEffect>();
        ClassPreview preview = new ClassPreview(new ClassChoice("mage", "Mage", Material.POTION, 500), inventory, armor, original, effects);
        inventory[8] = null; armor[3] = null;
        assertSame(firstRead, preview.inventory()[8]);
        assertSame(secondRead, preview.inventory()[8]);
        assertNotNull(preview.armor()[3]);
        assertNotNull(preview.offhand());
        assertNotSame(preview.inventory(), preview.inventory());
        assertEquals(List.of(), preview.effects());
        assertThrows(UnsupportedOperationException.class, () -> preview.effects().add(null));
        verify(original, times(3)).clone();
    }
}
