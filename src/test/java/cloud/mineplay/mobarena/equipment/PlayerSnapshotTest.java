package cloud.mineplay.mobarena.equipment;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerSnapshotTest {
    private static ItemStack item(Material material, int amount) {
        ItemStack item = mock(ItemStack.class);
        // Paper's real Material.isAir consults the live registry. This test exercises
        // replacement ordering; real NBT/material behavior is covered by staging tests.
        Material materialIdentity = mock(Material.class);
        when(item.getType()).thenReturn(materialIdentity);
        when(item.getAmount()).thenReturn(amount);
        when(item.clone()).thenReturn(item);
        return item;
    }

    @Test void restoringTwiceReplacesClassItemsRatherThanAddingDuplicateOriginalItems() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        World world = mock(World.class);
        ItemStack originalSword = item(Material.DIAMOND_SWORD, 1);
        ItemStack originalFood = item(Material.COOKED_BEEF, 12);
        ItemStack originalHelmet = item(Material.DIAMOND_HELMET, 1);
        ItemStack originalShield = item(Material.SHIELD, 1);
        ItemStack[] originals = new ItemStack[36];
        originals[0] = originalSword; originals[1] = originalFood;
        AtomicReference<ItemStack[]> storage = new AtomicReference<>(originals);
        AtomicReference<ItemStack[]> armor = new AtomicReference<>(new ItemStack[] { null, null, null, originalHelmet });
        AtomicReference<ItemStack> offhand = new AtomicReference<>(originalShield);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenAnswer(call -> storage.get());
        when(inventory.getArmorContents()).thenAnswer(call -> armor.get());
        when(inventory.getItemInOffHand()).thenAnswer(call -> offhand.get());
        when(inventory.getHeldItemSlot()).thenReturn(3);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getLocation()).thenReturn(new Location(world, 10, 64, 20));
        when(player.getActivePotionEffects()).thenReturn(List.of());
        doAnswer(call -> { storage.set(call.getArgument(0)); return null; }).when(inventory).setStorageContents(any(ItemStack[].class));
        doAnswer(call -> { armor.set(call.getArgument(0)); return null; }).when(inventory).setArmorContents(any(ItemStack[].class));
        doAnswer(call -> { offhand.set(call.getArgument(0)); return null; }).when(inventory).setItemInOffHand(any());

        PlayerSnapshot snapshot = PlayerSnapshot.capture(player, false);
        // Simulate a class kit and then a crash after the first original replacement.
        ItemStack[] kit = new ItemStack[36]; kit[0] = item(Material.IRON_SWORD, 1);
        storage.set(kit); armor.set(new ItemStack[4]); offhand.set(null);
        snapshot.replaceInventory(player);
        ItemStack[] firstReplacement = storage.get();
        snapshot.replaceInventory(player);

        assertNotSame(firstReplacement, storage.get());
        assertSame(originalSword, storage.get()[0]);
        assertSame(originalFood, storage.get()[1]);
        assertEquals(12, storage.get()[1].getAmount());
        assertEquals(2, java.util.Arrays.stream(storage.get()).filter(java.util.Objects::nonNull).count());
        assertSame(originalHelmet, armor.get()[3]);
        assertSame(originalShield, offhand.get());
        verify(inventory, times(2)).setHeldItemSlot(3);
        verify(inventory, never()).addItem(any(ItemStack[].class));
        verify(player, times(2)).setItemOnCursor(null);
    }

    private static ItemStack arrows(int amount, ItemStack comparison) {
        ItemStack arrows = item(Material.ARROW, amount);
        when(arrows.isSimilar(comparison)).thenReturn(true);
        return arrows;
    }

    private static ItemStack crossbow(ItemStack... chargedProjectiles) {
        ItemStack bow = item(Material.CROSSBOW, 1);
        CrossbowMeta meta = mock(CrossbowMeta.class);
        when(meta.getChargedProjectiles()).thenReturn(List.of(chargedProjectiles));
        when(bow.getItemMeta()).thenReturn(meta);
        return bow;
    }

    @Test void anArrowLoadedIntoACrossbowIsNotRefundedAsAnExpendedArrow() {
        ItemStack type = item(Material.ARROW,1);
        ItemStack[] original = { arrows(64,type),crossbow() };
        ItemStack[] current = { arrows(63,type),crossbow(arrows(1,type)) };
        assertEquals(0,PlayerSnapshot.refundableAmount(original,current,type));
    }

    @Test void multishotCountsOnePaidArrowDespiteThreeChargedProjectiles() {
        ItemStack type = item(Material.ARROW,1);
        ItemStack[] original = { arrows(64,type),crossbow() };
        ItemStack[] current = { arrows(62,type),crossbow(arrows(1,type),arrows(1,type),arrows(1,type)) };
        assertEquals(1,PlayerSnapshot.refundableAmount(original,current,type));
    }

    @Test void firingAnOriginallyChargedMultishotCrossbowRefundsOneArrow() {
        ItemStack type = item(Material.ARROW,1);
        ItemStack[] original = { crossbow(arrows(1,type),arrows(1,type),arrows(1,type)) };
        ItemStack[] current = { crossbow() };
        assertEquals(1,PlayerSnapshot.refundableAmount(original,current,type));
    }

    @Test void nonemptyCraftingGridIsRejectedBeforeClosingCanSpillItems() {
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        CraftingInventory crafting = mock(CraftingInventory.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(crafting);
        ItemStack diamond = item(Material.DIAMOND,1);
        when(crafting.getMatrix()).thenReturn(new ItemStack[] { diamond,null,null,null });
        assertTrue(EquipmentService.carriedInventoryError(player).contains("crafting grid"));
        verify(player,never()).closeInventory();
        verify(player,never()).getInventory();
    }

    @Test void emptyCraftingGridAndCursorMayProceedToDurablePreparation() {
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        CraftingInventory crafting = mock(CraftingInventory.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(crafting);
        when(crafting.getMatrix()).thenReturn(new ItemStack[4]);
        assertNull(EquipmentService.carriedInventoryError(player));
    }
}
