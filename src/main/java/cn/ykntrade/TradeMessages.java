package cn.ykntrade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;
import java.util.UUID;

final class TradeMessages {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final YknTradePlugin plugin;

    TradeMessages(YknTradePlugin plugin) {
        this.plugin = plugin;
    }

    Component requestReceived(String requester, UUID requestToken) {
        Component accept = message("request-accept-button", "&a<点此接受>", Map.of())
                .clickEvent(ClickEvent.runCommand("/ykntrade accept " + requestToken))
                .hoverEvent(HoverEvent.showText(message(
                        "request-accept-hover", "&e点击接受交易", Map.of())));
        String template = raw("request-received", "&e<{requester}>向你发起交易申请 {accept}")
                .replace("{requester}", requester);
        return insertComponent(template, "{accept}", accept);
    }

    Component requestSent(String target) {
        return message("request-sent", "&a已向 {target} 发起交易申请。", Map.of("target", target));
    }

    Component requestExpired() {
        return message("request-expired", "&e交易请求已过期", Map.of());
    }

    Component tradeSuccess() {
        return message("trade-success", "&a交易成功！", Map.of());
    }

    Component tradeCancelledClose() {
        return message("trade-cancelled-close", "&e交易已取消：有玩家关闭了交易界面。", Map.of());
    }

    Component tradeCancelledOffline() {
        return message("trade-cancelled-offline", "&e交易已取消：有玩家离线。", Map.of());
    }

    Component tradeCancelledShutdown() {
        return message("trade-cancelled-shutdown", "&e交易已取消：插件正在关闭。", Map.of());
    }

    private Component message(String key, String fallback, Map<String, String> placeholders) {
        String value = raw(key, fallback);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return LEGACY.deserialize(value);
    }

    private String raw(String key, String fallback) {
        return plugin.getConfig().getString("messages." + key, fallback);
    }

    private static Component insertComponent(String template, String token, Component inserted) {
        Component result = Component.empty();
        int start = 0;
        int index;
        boolean found = false;
        while ((index = template.indexOf(token, start)) >= 0) {
            result = result.append(LEGACY.deserialize(template.substring(start, index))).append(inserted);
            start = index + token.length();
            found = true;
        }
        result = result.append(LEGACY.deserialize(template.substring(start)));
        return found ? result : result.append(Component.space()).append(inserted);
    }
}
