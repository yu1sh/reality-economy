package io.github.yu1sh.reality.economy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/** A slotless menu whose owner and request identities are server-checked. */
public final class EconomyMenu extends AbstractContainerMenu {
    private static final int MAX_REQUEST_IDENTITIES = 256;
    private final UUID ownerId;
    private final Set<UUID> requestIds = new HashSet<>();
    private EconomyNetwork.EconomySnapshot snapshot;

    public EconomyMenu(MenuType<?> menuType, int containerId, Inventory inventory) {
        super(menuType, containerId);
        this.ownerId = inventory.player.getUUID();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return ownerId.equals(player.getUUID());
    }

    boolean ownedBy(UUID playerId) {
        return ownerId.equals(playerId);
    }

    synchronized boolean claimRequest(UUID requestId) {
        if (requestId == null || requestIds.contains(requestId) || requestIds.size() >= MAX_REQUEST_IDENTITIES) {
            return false;
        }
        return requestIds.add(requestId);
    }

    public EconomyNetwork.EconomySnapshot snapshot() {
        return snapshot;
    }

    public void setSnapshot(EconomyNetwork.EconomySnapshot snapshot) {
        this.snapshot = snapshot;
    }
}
