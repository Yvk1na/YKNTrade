package cn.ykntrade;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.UUID;

final class DropProtectionListener implements Listener {
    private final NamespacedKey ownerKey;

    DropProtectionListener(YknTradePlugin plugin) {
        this.ownerKey = new NamespacedKey(plugin, "drop_owner");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        markOwner(event.getItemDrop(), event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        for (Item item : event.getItems()) {
            markOwner(item, owner);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID owner = readOwner(event.getItem());
        if (owner != null && !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerPickupCompleted(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getRemaining() != 0) {
            return;
        }
        UUID owner = readOwner(event.getItem());
        if (owner != null && owner.equals(player.getUniqueId())) {
            clearOwner(event.getItem());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        UUID sourceOwner = readOwner(event.getEntity());
        UUID targetOwner = readOwner(event.getTarget());
        if (sourceOwner == null && targetOwner == null) {
            return;
        }
        if (!Objects.equals(sourceOwner, targetOwner)) {
            event.setCancelled(true);
            return;
        }
        markOwner(event.getTarget(), sourceOwner);
    }

    private void markOwner(Item item, UUID owner) {
        if (owner != null) {
            item.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        }
    }

    private void clearOwner(Item item) {
        item.getPersistentDataContainer().remove(ownerKey);
    }

    private UUID readOwner(Item item) {
        PersistentDataContainer data = item.getPersistentDataContainer();
        String value = data.get(ownerKey, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            data.remove(ownerKey);
            return null;
        }
    }
}
