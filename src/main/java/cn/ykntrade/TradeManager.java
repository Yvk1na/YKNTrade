package cn.ykntrade;

import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.sign.Side;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class TradeManager implements Listener {
    private static final long REQUEST_LIFETIME_MILLIS = 60_000L;
    private static final long REQUEST_LIFETIME_TICKS = 60L * 20L;
    private static final int HISTORY_PAGE_SIZE = 45;
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int PAGE_INFO_SLOT = 49;
    private static final int NEXT_PAGE_SLOT = 53;

    private final YknTradePlugin plugin;
    private final Economy economy;
    private final TradeMessages messages;
    private final TradeHistory tradeHistory;
    private final long confirmWaitTicks;
    private final Map<UUID, TradeRequest> requestsByTarget = new HashMap<>();
    private final Map<UUID, TradeRequest> requestsByRequester = new HashMap<>();
    private final Map<UUID, Long> requestCooldowns = new HashMap<>();
    private final Map<UUID, TradeSession> sessionsById = new HashMap<>();
    private final Map<UUID, TradeSession> sessionsByPlayer = new HashMap<>();
    private final Map<UUID, VirtualSignPrompt> balancePrompts = new HashMap<>();
    private final Set<UUID> ignoredTradeCloses = new HashSet<>();

    TradeManager(YknTradePlugin plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.messages = new TradeMessages(plugin);
        this.confirmWaitTicks = Math.max(1, plugin.getConfig().getLong("confirm-wait-seconds", 3)) * 20L;
        this.tradeHistory = new TradeHistory(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, tradeHistory::deleteExpired,
                60L * 20L, 60L * 20L);
    }

    YknTradePlugin plugin() {
        return plugin;
    }

    Economy economy() {
        return economy;
    }

    TradeMessages messages() {
        return messages;
    }

    long confirmWaitTicks() {
        return confirmWaitTicks;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSneakRightClick(PlayerInteractEntityEvent event) {
        Player requester = event.getPlayer();
        Entity clicked = event.getRightClicked();
        if (event.getHand() != EquipmentSlot.HAND || !requester.isSneaking() || !(clicked instanceof Player target)) {
            return;
        }
        if (target.hasMetadata("NPC")) {
            requester.sendMessage(Component.text("无法向 NPC 发起交易申请。", NamedTextColor.RED));
            return;
        }
        event.setCancelled(true);

        if (!requester.hasPermission("ykntrade.use")) {
            requester.sendMessage(Component.text("你没有使用交易功能的权限。", NamedTextColor.RED));
            return;
        }
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            return;
        }

        TradeRequest reverseRequest = requestsByTarget.get(requester.getUniqueId());
        if (reverseRequest != null && reverseRequest.requester().equals(target.getUniqueId())) {
            if (isExpired(reverseRequest)) {
                expireRequest(reverseRequest);
            } else {
                acceptRequest(requester, reverseRequest.token().toString());
                return;
            }
        }

        TradeRequest outgoingRequest = requestsByRequester.get(requester.getUniqueId());
        if (outgoingRequest != null) {
            if (isExpired(outgoingRequest)) {
                expireRequest(outgoingRequest);
            } else {
                requester.sendMessage(Component.text(
                        "你已有一个等待处理的交易请求，一分钟内无法再次发起。", NamedTextColor.RED));
                return;
            }
        }

        Long cooldownUntil = requestCooldowns.get(requester.getUniqueId());
        if (cooldownUntil != null) {
            if (cooldownUntil <= System.currentTimeMillis()) {
                requestCooldowns.remove(requester.getUniqueId(), cooldownUntil);
            } else {
                requester.sendMessage(Component.text(
                        "交易请求仍在一分钟冷却中，暂时无法再次发起。", NamedTextColor.RED));
                return;
            }
        }

        TradeRequest incomingRequest = requestsByTarget.get(target.getUniqueId());
        if (incomingRequest != null) {
            if (isExpired(incomingRequest)) {
                expireRequest(incomingRequest);
            } else {
                requester.sendMessage(Component.text("对方已有一个等待处理的交易请求。", NamedTextColor.RED));
                return;
            }
        }
        if (sessionsByPlayer.containsKey(requester.getUniqueId())) {
            requester.sendMessage(Component.text("你已经在一场交易中。", NamedTextColor.RED));
            return;
        }
        if (sessionsByPlayer.containsKey(target.getUniqueId())) {
            requester.sendMessage(Component.text("对方正在进行其他交易。", NamedTextColor.RED));
            return;
        }

        TradeRequest request = new TradeRequest(UUID.randomUUID(), requester.getUniqueId(),
                target.getUniqueId(), System.currentTimeMillis() + REQUEST_LIFETIME_MILLIS);
        requestsByTarget.put(target.getUniqueId(), request);
        requestsByRequester.put(requester.getUniqueId(), request);
        requestCooldowns.put(requester.getUniqueId(), request.expiresAtMillis());

        target.sendMessage(messages.requestReceived(requester.getName(), request.token()));
        requester.sendMessage(messages.requestSent(target.getName()));

        Bukkit.getScheduler().runTaskLater(plugin, () -> expireRequest(request), REQUEST_LIFETIME_TICKS);
    }

    void acceptRequest(Player target, String tokenText) {
        TradeRequest request = requestsByTarget.get(target.getUniqueId());
        if (request == null || !request.token().toString().equalsIgnoreCase(tokenText)) {
            target.sendMessage(Component.text("该交易申请不存在或已经失效。", NamedTextColor.RED));
            return;
        }
        if (isExpired(request)) {
            expireRequest(request);
            return;
        }
        removeRequest(request);

        Player requester = Bukkit.getPlayer(request.requester());
        if (requester == null || !requester.isOnline()) {
            target.sendMessage(Component.text("发起交易的玩家已经离线。", NamedTextColor.RED));
            return;
        }
        if (sessionsByPlayer.containsKey(requester.getUniqueId())
                || sessionsByPlayer.containsKey(target.getUniqueId())) {
            target.sendMessage(Component.text("其中一名玩家已经在进行其他交易。", NamedTextColor.RED));
            return;
        }

        removeRequestsInvolving(requester.getUniqueId());
        removeRequestsInvolving(target.getUniqueId());
        TradeSession session = new TradeSession(this, requester, target);
        sessionsById.put(session.id(), session);
        sessionsByPlayer.put(requester.getUniqueId(), session);
        sessionsByPlayer.put(target.getUniqueId(), session);
        session.open();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory.getHolder(false) instanceof AdminHistoryHolder holder) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player
                    && event.getClickedInventory() != null
                    && event.getClickedInventory().equals(topInventory)) {
                ItemStack clickedItem = event.getCurrentItem();
                if (event.getSlot() == PREVIOUS_PAGE_SLOT && holder.page() > 0
                        && clickedItem != null && clickedItem.getType() == Material.ARROW) {
                    Bukkit.getScheduler().runTask(plugin,
                            () -> openAdminHistory(player, holder.page() - 1));
                } else if (event.getSlot() == NEXT_PAGE_SLOT
                        && clickedItem != null && clickedItem.getType() == Material.ARROW) {
                    Bukkit.getScheduler().runTask(plugin,
                            () -> openAdminHistory(player, holder.page() + 1));
                }
            }
            return;
        }
        TradeView view = resolve(topInventory);
        if (view == null || !(event.getWhoClicked() instanceof Player player)
                || !view.viewerId().equals(player.getUniqueId())) {
            return;
        }

        if (event.getClick() == ClickType.DOUBLE_CLICK
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        if (clicked.equals(event.getView().getTopInventory())) {
            int slot = event.getSlot();
            if (slot == TradeSession.BALANCE_CONTROL_SLOT) {
                event.setCancelled(true);
                openBalanceSign(player, view.session());
                return;
            }
            if (slot == TradeSession.CONFIRM_SLOT) {
                event.setCancelled(true);
                view.session().confirm(player);
                return;
            }
            if (!TradeSession.isOwnOfferSlot(slot)) {
                event.setCancelled(true);
                return;
            }
            scheduleOfferCapture(view.session(), player.getUniqueId());
            return;
        }

        if (event.isShiftClick() || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                view.session().shiftIntoOffer(player, clicked, event.getSlot());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof AdminHistoryHolder) {
            event.setCancelled(true);
            return;
        }
        TradeView view = resolve(event.getView().getTopInventory());
        if (view == null || !view.viewerId().equals(event.getWhoClicked().getUniqueId())) {
            return;
        }
        boolean touchesOffer = false;
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < event.getView().getTopInventory().getSize()) {
                if (!TradeSession.isOwnOfferSlot(rawSlot)) {
                    event.setCancelled(true);
                    return;
                }
                touchesOffer = true;
            }
        }
        if (touchesOffer) {
            scheduleOfferCapture(view.session(), view.viewerId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        TradeView view = resolve(event.getInventory());
        if (view != null && !view.session().isEnded()) {
            if (ignoredTradeCloses.remove(view.viewerId())) {
                return;
            }
            view.session().captureOffer(view.viewerId());
            view.session().cancel(messages.tradeCancelledClose());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clearBalancePrompt(event.getPlayer().getUniqueId());
        ignoredTradeCloses.remove(event.getPlayer().getUniqueId());
        TradeSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session != null && !session.isEnded()) {
            session.cancel(messages.tradeCancelledOffline());
        }
        removeRequestsInvolving(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onVirtualSignSubmit(UncheckedSignChangeEvent event) {
        Player player = event.getPlayer();
        VirtualSignPrompt prompt = balancePrompts.get(player.getUniqueId());
        if (prompt == null || !sameBlock(prompt.location(), event.getEditedBlockPosition())) {
            return;
        }
        event.setCancelled(true);
        balancePrompts.remove(player.getUniqueId());
        restoreRealBlock(player, prompt.location());

        TradeSession session = sessionsById.get(prompt.sessionId());
        if (session == null || session.isEnded() || !session.hasPlayer(player.getUniqueId())) {
            return;
        }
        String input = event.lines().isEmpty() ? ""
                : PlainTextComponentSerializer.plainText().serialize(event.lines().getFirst()).trim();
        try {
            BigDecimal amount = MoneyAmount.parse(input);
            session.setAmount(player, amount);
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
        }
        reopenTradeView(player, session);
    }

    private void openBalanceSign(Player player, TradeSession session) {
        if (session.isEnded() || !session.hasPlayer(player.getUniqueId())) {
            return;
        }
        clearBalancePrompt(player.getUniqueId());
        String initial = MoneyAmount.plain(session.amount(player.getUniqueId()));
        Location location = virtualSignLocation(player);
        BlockData signData = Material.OAK_SIGN.createBlockData();
        BlockState state = signData.createBlockState();
        if (!(state instanceof Sign sign)) {
            player.sendMessage(Component.text("无法创建余额输入界面。", NamedTextColor.RED));
            return;
        }
        sign.getSide(Side.FRONT).line(0, Component.text(initial));
        sign.getSide(Side.FRONT).line(2, Component.text("支持 K M B T"));
        sign.getSide(Side.FRONT).line(3, Component.text("完成后点击确定"));
        sign.setAllowedEditorUniqueId(player.getUniqueId());
        sign.setWaxed(false);

        balancePrompts.put(player.getUniqueId(), new VirtualSignPrompt(session.id(), location));
        ignoreNextTradeClose(player.getUniqueId());
        try {
            player.sendBlockChange(location, signData);
            player.sendBlockUpdate(location, sign);
            player.openVirtualSign(Position.block(location), Side.FRONT);
        } catch (RuntimeException ex) {
            balancePrompts.remove(player.getUniqueId());
            restoreRealBlock(player, location);
            player.sendMessage(Component.text("无法打开余额输入告示牌，请重试。", NamedTextColor.RED));
            plugin.getLogger().warning("打开虚拟告示牌失败: " + ex.getMessage());
        }
    }

    private void reopenTradeView(Player player, TradeSession session) {
        if (!session.isEnded() && player.isOnline()) {
            ignoreNextTradeClose(player.getUniqueId());
            session.reopen(player);
        }
    }

    private void ignoreNextTradeClose(UUID playerId) {
        ignoredTradeCloses.add(playerId);
        Bukkit.getScheduler().runTaskLater(plugin, () -> ignoredTradeCloses.remove(playerId), 2L);
    }

    private static Location virtualSignLocation(Player player) {
        int y = Math.max(player.getWorld().getMinHeight(), player.getLocation().getBlockY() - 2);
        return new Location(player.getWorld(), player.getLocation().getBlockX(), y,
                player.getLocation().getBlockZ());
    }

    private static boolean sameBlock(Location location, Position position) {
        return location.getBlockX() == position.blockX()
                && location.getBlockY() == position.blockY()
                && location.getBlockZ() == position.blockZ();
    }

    private static void restoreRealBlock(Player player, Location location) {
        if (!player.getWorld().equals(location.getWorld())) {
            return;
        }
        BlockData realData = location.getBlock().getBlockData();
        player.sendBlockChange(location, realData);
        BlockState realState = location.getBlock().getState();
        if (realState instanceof TileState tileState) {
            player.sendBlockUpdate(location, tileState);
        }
    }

    private void clearBalancePrompt(UUID playerId) {
        VirtualSignPrompt prompt = balancePrompts.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (prompt != null && player != null) {
            restoreRealBlock(player, prompt.location());
        }
    }

    private void scheduleOfferCapture(TradeSession session, UUID playerId) {
        Bukkit.getScheduler().runTask(plugin, () -> session.captureOffer(playerId));
    }

    void openAdminHistory(Player player) {
        openAdminHistory(player, 0);
    }

    private void openAdminHistory(Player player, int requestedPage) {
        if (!player.hasPermission("ykntrade.admin")) {
            player.sendMessage(Component.text("你没有查看交易记录的权限。", NamedTextColor.RED));
            return;
        }

        List<TradeRecord> records = tradeHistory.recent();
        int pageCount = Math.max(1, (records.size() + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));

        AdminHistoryHolder holder = new AdminHistoryHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("交易记录 " + (page + 1) + "/" + pageCount, NamedTextColor.DARK_GREEN)
                        .append(Component.text(" | MADE BY Yvk1na", NamedTextColor.GOLD)));
        holder.bind(inventory);

        if (records.isEmpty()) {
            inventory.setItem(22, historyPaper(null, 0));
        } else {
            int start = page * HISTORY_PAGE_SIZE;
            int end = Math.min(start + HISTORY_PAGE_SIZE, records.size());
            for (int index = start; index < end; index++) {
                inventory.setItem(index - start, historyPaper(records.get(index), index + 1));
            }
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS_PAGE_SLOT,
                    navigationItem(Material.ARROW, "上一页", NamedTextColor.YELLOW));
        }
        inventory.setItem(PAGE_INFO_SLOT, pageInfoItem(page, pageCount, records.size()));
        if (page + 1 < pageCount) {
            inventory.setItem(NEXT_PAGE_SLOT,
                    navigationItem(Material.ARROW, "下一页", NamedTextColor.GREEN));
        }
        player.openInventory(inventory);
    }

    void recordSuccessfulTrade(Player first, Player second,
                               List<ItemStack> firstOffer, List<ItemStack> secondOffer,
                               BigDecimal firstMoney, BigDecimal secondMoney) {
        tradeHistory.add(new TradeRecord(
                System.currentTimeMillis(),
                first.getUniqueId().toString(), first.getName(),
                second.getUniqueId().toString(), second.getName(),
                MoneyAmount.format(firstMoney), MoneyAmount.format(secondMoney),
                summarizeItems(firstOffer), summarizeItems(secondOffer)
        ));
    }

    private static ItemStack historyPaper(TradeRecord record, int number) {
        ItemStack paper = new ItemStack(Material.PAPER);
        paper.editMeta(meta -> {
            if (record == null) {
                meta.displayName(noItalic(Component.text("暂无成功交易记录", NamedTextColor.GRAY)));
                meta.lore(List.of(noItalic(Component.text("成功完成交易后会在这里显示。",
                        NamedTextColor.DARK_GRAY))));
                return;
            }

            meta.displayName(noItalic(Component.text("#" + number + "  ", NamedTextColor.GRAY)
                    .append(Component.text(record.firstPlayerName(), NamedTextColor.GREEN))
                    .append(Component.text(" ↔ ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(record.secondPlayerName(), NamedTextColor.AQUA))));
            List<Component> lore = new ArrayList<>();
            lore.add(loreLine("时间: " + TradeHistory.formatBeijingTime(record.timestamp()) + "（北京时间）",
                    NamedTextColor.GRAY));
            lore.add(Component.empty());
            addPlayerOfferLore(lore, record.firstPlayerName(), record.firstMoney(), record.firstItems(),
                    NamedTextColor.GREEN);
            lore.add(Component.empty());
            addPlayerOfferLore(lore, record.secondPlayerName(), record.secondMoney(), record.secondItems(),
                    NamedTextColor.AQUA);
            meta.lore(lore);
        });
        return paper;
    }

    private static ItemStack navigationItem(Material material, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(noItalic(Component.text(name, color))));
        return item;
    }

    private static ItemStack pageInfoItem(int page, int pageCount, int recordCount) {
        ItemStack item = new ItemStack(Material.CLOCK);
        item.editMeta(meta -> {
            meta.displayName(noItalic(Component.text(
                    "第 " + (page + 1) + " / " + pageCount + " 页", NamedTextColor.GOLD)));
            meta.lore(List.of(
                    loreLine("共 " + recordCount + " 条交易记录", NamedTextColor.GRAY),
                    loreLine("仅保留最近 7 天", NamedTextColor.DARK_GRAY)
            ));
        });
        return item;
    }

    private static void addPlayerOfferLore(List<Component> lore, String playerName, String money,
                                           List<String> items, NamedTextColor color) {
        lore.add(loreLine(playerName + " 提供:", color));
        if (items.isEmpty()) {
            lore.add(loreLine("  • 无物品", NamedTextColor.DARK_GRAY));
        } else {
            for (String item : items) {
                lore.add(loreLine("  • " + item, NamedTextColor.WHITE));
            }
        }
        lore.add(loreLine("  • 余额: " + money, NamedTextColor.GOLD));
    }

    private static List<String> summarizeItems(List<ItemStack> items) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (ItemStack stack : items) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            String label = stack.getType().getKey().getKey();
            ItemMeta meta = stack.getItemMeta();
            if (meta.hasDisplayName() && meta.displayName() != null) {
                String customName = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
                if (!customName.isBlank()) {
                    label = customName + " (" + label + ")";
                }
            }
            totals.merge(label, stack.getAmount(), Integer::sum);
        }
        return totals.entrySet().stream()
                .map(entry -> entry.getKey() + " × " + entry.getValue())
                .toList();
    }

    private static Component loreLine(String text, NamedTextColor color) {
        return noItalic(Component.text(text, color));
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private TradeView resolve(Inventory inventory) {
        if (!(inventory.getHolder(false) instanceof TradeInventoryHolder holder)) {
            return null;
        }
        TradeSession session = sessionsById.get(holder.sessionId());
        if (session == null || session.isEnded()) {
            return null;
        }
        return new TradeView(session, holder.viewerId());
    }

    void unregister(TradeSession session) {
        sessionsById.remove(session.id(), session);
        for (UUID playerId : session.playerIds()) {
            sessionsByPlayer.remove(playerId, session);
            requestCooldowns.remove(playerId);
            clearBalancePrompt(playerId);
            ignoredTradeCloses.remove(playerId);
        }
    }

    private void removeRequestsInvolving(UUID playerId) {
        for (TradeRequest request : new ArrayList<>(requestsByTarget.values())) {
            if (request.target().equals(playerId) || request.requester().equals(playerId)) {
                removeRequest(request);
            }
        }
    }

    private boolean isExpired(TradeRequest request) {
        return request.expiresAtMillis() <= System.currentTimeMillis();
    }

    private boolean removeRequest(TradeRequest request) {
        boolean removed = requestsByTarget.remove(request.target(), request);
        requestsByRequester.remove(request.requester(), request);
        return removed;
    }

    private void expireRequest(TradeRequest request) {
        boolean removed = removeRequest(request);
        requestCooldowns.remove(request.requester(), request.expiresAtMillis());
        if (!removed) {
            return;
        }
        Component message = messages.requestExpired();
        Player requester = Bukkit.getPlayer(request.requester());
        Player target = Bukkit.getPlayer(request.target());
        if (requester != null) {
            requester.sendMessage(message);
        }
        if (target != null) {
            target.sendMessage(message);
        }
    }

    void shutdown() {
        for (TradeSession session : new ArrayList<>(sessionsById.values())) {
            session.cancel(messages.tradeCancelledShutdown());
        }
        requestsByTarget.clear();
        requestsByRequester.clear();
        requestCooldowns.clear();
        balancePrompts.clear();
        ignoredTradeCloses.clear();
    }

    private record TradeRequest(UUID token, UUID requester, UUID target, long expiresAtMillis) {
    }

    private record TradeView(TradeSession session, UUID viewerId) {
    }

    private record VirtualSignPrompt(UUID sessionId, Location location) {
    }
}
