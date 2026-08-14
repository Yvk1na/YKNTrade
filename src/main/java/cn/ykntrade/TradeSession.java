package cn.ykntrade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class TradeSession {
    static final int BALANCE_CONTROL_SLOT = 36;
    static final int CONFIRM_SLOT = 39;

    private static final int[] OWN_OFFER_SLOTS = {
            0, 1, 2, 3,
            9, 10, 11, 12,
            18, 19, 20, 21,
            27, 28, 29, 30
    };
    private static final int[] OPPONENT_OFFER_SLOTS = {
            5, 6, 7, 8,
            14, 15, 16, 17,
            23, 24, 25, 26,
            32, 33, 34, 35
    };
    private static final int[] SEPARATOR_SLOTS = {4, 13, 22, 31, 40};
    private static final int OWN_MONEY_PRIMARY = 37;
    private static final int OWN_MONEY_SECONDARY = 38;
    private static final int OPPONENT_STATUS_SLOT = 41;
    private static final int OPPONENT_MONEY_PRIMARY = 42;
    private static final int OPPONENT_MONEY_SECONDARY = 43;

    private final TradeManager manager;
    private final UUID id = UUID.randomUUID();
    private final UUID firstId;
    private final UUID secondId;
    private final String firstName;
    private final String secondName;
    private final Map<UUID, Inventory> inventories = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> offers = new HashMap<>();
    private final Map<UUID, BigDecimal> amounts = new HashMap<>();
    private final Set<UUID> confirmed = new HashSet<>();
    private boolean ready = true;
    private boolean executing;
    private boolean ended;
    private long revision;

    TradeSession(TradeManager manager, Player first, Player second) {
        this.manager = manager;
        this.firstId = first.getUniqueId();
        this.secondId = second.getUniqueId();
        this.firstName = first.getName();
        this.secondName = second.getName();

        offers.put(firstId, emptyOffer());
        offers.put(secondId, emptyOffer());
        amounts.put(firstId, MoneyAmount.ZERO);
        amounts.put(secondId, MoneyAmount.ZERO);
        createInventory(firstId, secondName);
        createInventory(secondId, firstName);
    }

    UUID id() {
        return id;
    }

    Set<UUID> playerIds() {
        return Set.of(firstId, secondId);
    }

    boolean hasPlayer(UUID playerId) {
        return firstId.equals(playerId) || secondId.equals(playerId);
    }

    boolean isEnded() {
        return ended;
    }

    BigDecimal amount(UUID playerId) {
        return amounts.getOrDefault(playerId, MoneyAmount.ZERO);
    }

    static boolean isOwnOfferSlot(int slot) {
        for (int ownSlot : OWN_OFFER_SLOTS) {
            if (ownSlot == slot) {
                return true;
            }
        }
        return false;
    }

    void open() {
        render();
        Player first = Bukkit.getPlayer(firstId);
        Player second = Bukkit.getPlayer(secondId);
        if (first == null || second == null) {
            cancel(manager.messages().tradeCancelledOffline());
            return;
        }
        first.openInventory(inventories.get(firstId));
        second.openInventory(inventories.get(secondId));
    }

    void reopen(Player player) {
        if (!ended && hasPlayer(player.getUniqueId())) {
            render();
            player.openInventory(inventories.get(player.getUniqueId()));
        }
    }

    void captureOffer(UUID playerId) {
        if (ended || !hasPlayer(playerId)) {
            return;
        }
        Inventory inventory = inventories.get(playerId);
        List<ItemStack> captured = new ArrayList<>(OWN_OFFER_SLOTS.length);
        for (int slot : OWN_OFFER_SLOTS) {
            captured.add(copyOrNull(inventory.getItem(slot)));
        }
        if (!sameContents(offers.get(playerId), captured)) {
            offers.put(playerId, captured);
            markChanged();
        }
    }

    void shiftIntoOffer(Player player, Inventory sourceInventory, int sourceSlot) {
        UUID playerId = player.getUniqueId();
        if (ended || executing || !hasPlayer(playerId)
                || !sourceInventory.equals(player.getInventory())
                || sourceSlot < 0 || sourceSlot >= sourceInventory.getSize()) {
            return;
        }

        ItemStack sourceItem = copyOrNull(sourceInventory.getItem(sourceSlot));
        if (sourceItem == null) {
            return;
        }

        Inventory tradeInventory = inventories.get(playerId);
        int remaining = sourceItem.getAmount();

        for (int slot : OWN_OFFER_SLOTS) {
            ItemStack offered = tradeInventory.getItem(slot);
            if (offered == null || !offered.isSimilar(sourceItem)) {
                continue;
            }
            int maxStackSize = Math.min(offered.getMaxStackSize(), tradeInventory.getMaxStackSize());
            int moved = Math.min(remaining, maxStackSize - offered.getAmount());
            if (moved <= 0) {
                continue;
            }
            offered.setAmount(offered.getAmount() + moved);
            tradeInventory.setItem(slot, offered);
            remaining -= moved;
            if (remaining == 0) {
                break;
            }
        }

        if (remaining > 0) {
            for (int slot : OWN_OFFER_SLOTS) {
                ItemStack offered = tradeInventory.getItem(slot);
                if (offered != null && !offered.getType().isAir()) {
                    continue;
                }
                int moved = Math.min(remaining,
                        Math.min(sourceItem.getMaxStackSize(), tradeInventory.getMaxStackSize()));
                ItemStack placed = sourceItem.clone();
                placed.setAmount(moved);
                tradeInventory.setItem(slot, placed);
                remaining -= moved;
                if (remaining == 0) {
                    break;
                }
            }
        }

        if (remaining == sourceItem.getAmount()) {
            return;
        }
        if (remaining == 0) {
            sourceInventory.setItem(sourceSlot, null);
        } else {
            sourceItem.setAmount(remaining);
            sourceInventory.setItem(sourceSlot, sourceItem);
        }
        captureOffer(playerId);
    }

    void setAmount(Player player, BigDecimal rawAmount) {
        if (ended || !hasPlayer(player.getUniqueId())) {
            return;
        }

        BigDecimal amount = validatePrecision(rawAmount);
        double value = amount.doubleValue();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("金额超出可用范围");
        }
        if (!manager.economy().has(player, value)) {
            throw new IllegalArgumentException("余额不足，当前余额为 " + manager.economy().format(
                    manager.economy().getBalance(player)));
        }
        UUID playerId = player.getUniqueId();
        if (amount.compareTo(amounts.get(playerId)) == 0) {
            return;
        }

        amounts.put(playerId, amount);
        markChanged();
        player.sendMessage(Component.text("余额报价已设为 " + MoneyAmount.format(amount), NamedTextColor.GREEN));
    }

    void confirm(Player player) {
        if (ended || executing || !hasPlayer(player.getUniqueId())) {
            return;
        }
        captureOffer(firstId);
        captureOffer(secondId);
        if (!ready) {
            player.sendMessage(Component.text("报价刚刚发生变化，请等待 3 秒后再确认。", NamedTextColor.YELLOW));
            return;
        }
        if (!confirmed.add(player.getUniqueId())) {
            return;
        }
        render();
        if (confirmed.contains(firstId) && confirmed.contains(secondId)) {
            executeTrade();
        } else {
            player.sendMessage(Component.text("你已确认交易，正在等待对方。", NamedTextColor.GREEN));
        }
    }

    void cancel(Component reason) {
        if (ended) {
            return;
        }
        captureOffer(firstId);
        captureOffer(secondId);
        Map<UUID, List<ItemStack>> returned = snapshotOffers();

        ended = true;
        manager.unregister(this);
        clearOfferSlots();
        closeViews();

        for (UUID playerId : playerIds()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                giveItems(player, returned.get(playerId));
                player.sendMessage(reason);
            } else if (hasAnyItem(returned.get(playerId))) {
                manager.plugin().getLogger().severe("无法向离线玩家 " + playerId + " 退回交易物品。");
            }
        }
    }

    private void executeTrade() {
        if (executing || ended) {
            return;
        }
        executing = true;
        captureOffer(firstId);
        captureOffer(secondId);
        if (!ready || !confirmed.containsAll(playerIds())) {
            executing = false;
            return;
        }

        Player first = Bukkit.getPlayer(firstId);
        Player second = Bukkit.getPlayer(secondId);
        if (first == null || second == null) {
            executing = false;
            cancel(manager.messages().tradeCancelledOffline());
            return;
        }

        BigDecimal firstAmount = amounts.get(firstId);
        BigDecimal secondAmount = amounts.get(secondId);
        if (!manager.economy().has(first, firstAmount.doubleValue())
                || !manager.economy().has(second, secondAmount.doubleValue())) {
            failExecution("余额发生变化，其中一名玩家的余额不足。");
            return;
        }
        if (!transferBalances(first, second, firstAmount.doubleValue(), secondAmount.doubleValue())) {
            failExecution("经济插件拒绝了转账，请重试或联系管理员。");
            return;
        }

        Map<UUID, List<ItemStack>> finalOffers = snapshotOffers();
        manager.recordSuccessfulTrade(first, second,
                finalOffers.get(firstId), finalOffers.get(secondId), firstAmount, secondAmount);
        ended = true;
        manager.unregister(this);
        clearOfferSlots();
        closeViews();

        giveItems(first, finalOffers.get(secondId));
        giveItems(second, finalOffers.get(firstId));
        first.sendMessage(manager.messages().tradeSuccess());
        second.sendMessage(manager.messages().tradeSuccess());
    }

    private boolean transferBalances(Player first, Player second, double firstAmount, double secondAmount) {
        Economy economy = manager.economy();
        boolean firstWithdrawn = withdraw(economy, first, firstAmount);
        if (!firstWithdrawn) {
            return false;
        }
        boolean secondWithdrawn = withdraw(economy, second, secondAmount);
        if (!secondWithdrawn) {
            compensateDeposit(economy, first, firstAmount, "退还第一位玩家扣款");
            return false;
        }

        boolean paidSecond = deposit(economy, second, firstAmount);
        if (!paidSecond) {
            compensateDeposit(economy, first, firstAmount, "退还第一位玩家扣款");
            compensateDeposit(economy, second, secondAmount, "退还第二位玩家扣款");
            return false;
        }
        boolean paidFirst = deposit(economy, first, secondAmount);
        if (!paidFirst) {
            boolean reversedSecond = withdraw(economy, second, firstAmount);
            boolean restoredFirst = compensateDeposit(economy, first, firstAmount, "恢复第一位玩家余额");
            boolean restoredSecond = compensateDeposit(economy, second, secondAmount, "恢复第二位玩家余额");
            if (!reversedSecond || !restoredFirst || !restoredSecond) {
                manager.plugin().getLogger().severe("Vault 转账补偿未能完整完成，会话: " + id);
            }
            return false;
        }
        return true;
    }

    private static boolean withdraw(Economy economy, OfflinePlayer player, double amount) {
        if (amount == 0D) {
            return true;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    private static boolean deposit(Economy economy, OfflinePlayer player, double amount) {
        if (amount == 0D) {
            return true;
        }
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    private boolean compensateDeposit(Economy economy, OfflinePlayer player, double amount, String operation) {
        boolean success = deposit(economy, player, amount);
        if (!success) {
            manager.plugin().getLogger().severe(operation + "失败，会话: " + id + ", 玩家: " + player.getUniqueId()
                    + ", 金额: " + amount);
        }
        return success;
    }

    private void failExecution(String message) {
        executing = false;
        confirmed.clear();
        ready = true;
        render();
        messageBoth(Component.text(message, NamedTextColor.RED));
    }

    private void markChanged() {
        if (ended) {
            return;
        }
        confirmed.clear();
        ready = false;
        long expectedRevision = ++revision;
        render();
        Bukkit.getScheduler().runTaskLater(manager.plugin(), () -> {
            if (!ended && revision == expectedRevision) {
                ready = true;
                render();
            }
        }, manager.confirmWaitTicks());
    }

    private void render() {
        if (ended) {
            return;
        }
        for (UUID viewerId : playerIds()) {
            UUID opponentId = opponentOf(viewerId);
            Inventory inventory = inventories.get(viewerId);

            for (int separator : SEPARATOR_SLOTS) {
                inventory.setItem(separator, item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY));
            }
            List<ItemStack> opponentOffer = offers.get(opponentId);
            for (int i = 0; i < OPPONENT_OFFER_SLOTS.length; i++) {
                inventory.setItem(OPPONENT_OFFER_SLOTS[i], copyOrNull(opponentOffer.get(i)));
            }

            inventory.setItem(BALANCE_CONTROL_SLOT, balanceControl(viewerId));
            setMoneyDisplay(inventory, OWN_MONEY_PRIMARY, OWN_MONEY_SECONDARY, amounts.get(viewerId));
            inventory.setItem(CONFIRM_SLOT, confirmationButton(viewerId));
            inventory.setItem(OPPONENT_STATUS_SLOT, opponentStatus(opponentId));
            setMoneyDisplay(inventory, OPPONENT_MONEY_PRIMARY, OPPONENT_MONEY_SECONDARY,
                    amounts.get(opponentId));
        }
    }

    private ItemStack balanceControl(UUID viewerId) {
        Player player = Bukkit.getPlayer(viewerId);
        String balance = player == null ? "未知" : manager.economy().format(manager.economy().getBalance(player));
        return item(Material.GOLD_NUGGET, "使用余额交易", NamedTextColor.GOLD,
                List.of("当前余额: " + balance, "点击输入金额（支持 K / M / B / T）"));
    }

    private ItemStack confirmationButton(UUID viewerId) {
        if (confirmed.contains(viewerId)) {
            return item(Material.GREEN_STAINED_GLASS, "已确定 等待对方同意", NamedTextColor.GREEN);
        }
        if (!ready) {
            return item(Material.YELLOW_CONCRETE, "Waiting", NamedTextColor.YELLOW,
                    List.of("报价变化后需要等待 3 秒"));
        }
        return item(Material.GREEN_CONCRETE, "交易!", NamedTextColor.GREEN,
                List.of("点击确认当前报价"));
    }

    private ItemStack opponentStatus(UUID opponentId) {
        if (confirmed.contains(opponentId)) {
            return item(Material.LIME_DYE, "对方已确定", NamedTextColor.GREEN);
        }
        return item(Material.GRAY_DYE, "等待对方确定", NamedTextColor.GRAY);
    }

    private void setMoneyDisplay(Inventory inventory, int primarySlot, int secondarySlot, BigDecimal amount) {
        ItemStack[] items = moneyItems(amount);
        inventory.setItem(primarySlot, items[0]);
        inventory.setItem(secondarySlot, items[1]);
    }

    static ItemStack[] moneyItems(BigDecimal amount) {
        if (amount == null || amount.signum() == 0) {
            return new ItemStack[]{null, null};
        }
        String total = MoneyAmount.format(amount);
        if (amount.compareTo(MoneyAmount.ONE_BILLION) >= 0) {
            BigInteger blocks = amount.divideToIntegralValue(MoneyAmount.ONE_BILLION).toBigIntegerExact();
            int visibleAmount = Math.max(1, Math.min(64, blocks.min(BigInteger.valueOf(64)).intValue()));
            List<String> diamondLore = new ArrayList<>();
            if (blocks.compareTo(BigInteger.ONE) > 0) {
                diamondLore.add("数量: " + blocks + "（每个代表 1B）");
            }
            diamondLore.add("余额报价总额: " + total);
            ItemStack diamond = item(Material.DIAMOND_BLOCK, "1B", NamedTextColor.AQUA, diamondLore);
            diamond.setAmount(visibleAmount);

            BigDecimal remainder = amount.remainder(MoneyAmount.ONE_BILLION);
            ItemStack gold = remainder.signum() == 0 ? null
                    : item(Material.GOLD_BLOCK, MoneyAmount.format(remainder), NamedTextColor.GOLD,
                    List.of("余额报价的一部分"));
            return new ItemStack[]{diamond, gold};
        }
        Material material = amount.compareTo(MoneyAmount.ONE_HUNDRED_MILLION) >= 0
                ? Material.DIAMOND_BLOCK : Material.GOLD_BLOCK;
        NamedTextColor color = material == Material.DIAMOND_BLOCK ? NamedTextColor.AQUA : NamedTextColor.GOLD;
        return new ItemStack[]{item(material, total, color, List.of("余额报价")), null};
    }

    private void createInventory(UUID viewerId, String opponentName) {
        TradeInventoryHolder holder = new TradeInventoryHolder(id, viewerId);
        Component title = Component.text("You", NamedTextColor.WHITE)
                .append(Component.text("                 " + opponentName, NamedTextColor.GRAY));
        Inventory inventory = Bukkit.createInventory(holder, 45, title);
        holder.bind(inventory);
        inventories.put(viewerId, inventory);
    }

    private UUID opponentOf(UUID playerId) {
        return firstId.equals(playerId) ? secondId : firstId;
    }

    private BigDecimal validatePrecision(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("金额不能为负数");
        }
        int digits = manager.economy().fractionalDigits();
        if (digits >= 0) {
            try {
                amount = amount.setScale(digits, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("该经济系统最多支持 " + digits + " 位小数");
            }
        }
        return amount.stripTrailingZeros();
    }

    private void closeViews() {
        for (Map.Entry<UUID, Inventory> entry : inventories.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.getOpenInventory().getTopInventory().equals(entry.getValue())) {
                player.closeInventory();
            }
        }
    }

    private void clearOfferSlots() {
        for (Inventory inventory : inventories.values()) {
            for (int slot : OWN_OFFER_SLOTS) {
                inventory.setItem(slot, null);
            }
            for (int slot : OPPONENT_OFFER_SLOTS) {
                inventory.setItem(slot, null);
            }
        }
    }

    private Map<UUID, List<ItemStack>> snapshotOffers() {
        Map<UUID, List<ItemStack>> snapshot = new HashMap<>();
        for (UUID playerId : playerIds()) {
            List<ItemStack> copies = new ArrayList<>(OWN_OFFER_SLOTS.length);
            for (ItemStack stack : offers.get(playerId)) {
                copies.add(copyOrNull(stack));
            }
            snapshot.put(playerId, copies);
        }
        return snapshot;
    }

    private static void giveItems(Player player, List<ItemStack> items) {
        List<ItemStack> present = items.stream().filter(Objects::nonNull).map(ItemStack::clone).toList();
        if (present.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(present.toArray(ItemStack[]::new));
        if (!leftovers.isEmpty()) {
            leftovers.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
            player.sendMessage(Component.text("背包空间不足，剩余交易物品已掉落在脚下。", NamedTextColor.YELLOW));
        }
    }

    private static boolean hasAnyItem(List<ItemStack> items) {
        return items != null && items.stream().anyMatch(Objects::nonNull);
    }

    private static List<ItemStack> emptyOffer() {
        return new ArrayList<>(Arrays.asList(new ItemStack[OWN_OFFER_SLOTS.length]));
    }

    private static boolean sameContents(List<ItemStack> first, List<ItemStack> second) {
        if (first == null || first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            if (!Objects.equals(first.get(i), second.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack copyOrNull(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private void messageBoth(Component message) {
        for (UUID playerId : playerIds()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    private static ItemStack item(Material material, String name, NamedTextColor color) {
        return item(material, name, color, List.of());
    }

    private static ItemStack item(Material material, String name, NamedTextColor color, List<String> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) {
                meta.lore(lore.stream()
                        .map(line -> Component.text(line, NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false))
                        .toList());
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        });
        return item;
    }
}
