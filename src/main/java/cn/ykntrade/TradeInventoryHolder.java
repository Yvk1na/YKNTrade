package cn.ykntrade;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

final class TradeInventoryHolder implements InventoryHolder {
    private final UUID sessionId;
    private final UUID viewerId;
    private Inventory inventory;

    TradeInventoryHolder(UUID sessionId, UUID viewerId) {
        this.sessionId = sessionId;
        this.viewerId = viewerId;
    }

    UUID sessionId() {
        return sessionId;
    }

    UUID viewerId() {
        return viewerId;
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("Inventory has not been bound yet");
        }
        return inventory;
    }
}
