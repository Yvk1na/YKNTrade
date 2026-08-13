package cn.ykntrade;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

final class AdminHistoryHolder implements InventoryHolder {
    private final int page;
    private Inventory inventory;

    AdminHistoryHolder(int page) {
        this.page = page;
    }

    int page() {
        return page;
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
