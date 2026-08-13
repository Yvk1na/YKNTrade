package cn.ykntrade;

import java.util.List;

record TradeRecord(
        long timestamp,
        String firstPlayerId,
        String firstPlayerName,
        String secondPlayerId,
        String secondPlayerName,
        String firstMoney,
        String secondMoney,
        List<String> firstItems,
        List<String> secondItems
) {
    TradeRecord {
        firstItems = List.copyOf(firstItems);
        secondItems = List.copyOf(secondItems);
    }
}
