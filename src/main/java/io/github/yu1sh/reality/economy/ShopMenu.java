package io.github.yu1sh.reality.economy;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/** A slotless menu: all state-changing actions are explicit validated packets. */
public final class ShopMenu extends AbstractContainerMenu {
    private ShopNetwork.ShopSnapshot snapshot;

    public ShopMenu(MenuType<?> menuType, int containerId, Inventory inventory) {
        super(menuType, containerId);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    public ShopNetwork.ShopSnapshot snapshot() {
        return snapshot;
    }

    public void setSnapshot(ShopNetwork.ShopSnapshot snapshot) {
        this.snapshot = snapshot;
    }
}
