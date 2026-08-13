package cn.ykntrade;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TradeHistory {
    private static final String RECORDS_PATH = "records";
    private static final long RETENTION_MILLIS = Duration.ofDays(7).toMillis();
    private static final DateTimeFormatter BEIJING_TIME = DateTimeFormatter
            .ofPattern("yyyy年MM月dd日 HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final YknTradePlugin plugin;
    private final File file;
    private final Deque<TradeRecord> records = new ArrayDeque<>();

    TradeHistory(YknTradePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "history.yml");
        load();
    }

    List<TradeRecord> recent() {
        deleteExpired();
        return List.copyOf(records);
    }

    void add(TradeRecord record) {
        records.addFirst(record);
        removeExpiredFromMemory();
        save();
    }

    void deleteExpired() {
        if (removeExpiredFromMemory()) {
            save();
        }
    }

    static String formatBeijingTime(long timestamp) {
        return BEIJING_TIME.format(Instant.ofEpochMilli(timestamp));
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;
        for (Map<?, ?> values : yaml.getMapList(RECORDS_PATH)) {
            try {
                TradeRecord record = fromMap(values);
                if (isExpired(record.timestamp())) {
                    changed = true;
                } else {
                    records.addLast(record);
                }
            } catch (RuntimeException ex) {
                changed = true;
                plugin.getLogger().warning("跳过一条损坏的交易记录: " + ex.getMessage());
            }
        }
        if (changed) {
            save();
        }
    }

    private boolean removeExpiredFromMemory() {
        return records.removeIf(record -> isExpired(record.timestamp()));
    }

    private static boolean isExpired(long timestamp) {
        return timestamp < System.currentTimeMillis() - RETENTION_MILLIS;
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> serialized = records.stream().map(TradeHistory::toMap).toList();
        yaml.set(RECORDS_PATH, serialized);
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("无法保存交易记录 history.yml: " + ex.getMessage());
        }
    }

    private static TradeRecord fromMap(Map<?, ?> values) {
        Object rawTimestamp = values.get("timestamp");
        if (!(rawTimestamp instanceof Number timestamp)) {
            throw new IllegalArgumentException("缺少 timestamp");
        }
        return new TradeRecord(
                timestamp.longValue(),
                string(values, "first-player-id"),
                string(values, "first-player-name"),
                string(values, "second-player-id"),
                string(values, "second-player-name"),
                string(values, "first-money"),
                string(values, "second-money"),
                stringList(values.get("first-items")),
                stringList(values.get("second-items"))
        );
    }

    private static Map<String, Object> toMap(TradeRecord record) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("timestamp", record.timestamp());
        values.put("beijing-time", formatBeijingTime(record.timestamp()));
        values.put("first-player-id", record.firstPlayerId());
        values.put("first-player-name", record.firstPlayerName());
        values.put("second-player-id", record.secondPlayerId());
        values.put("second-player-name", record.secondPlayerName());
        values.put("first-money", record.firstMoney());
        values.put("second-money", record.secondMoney());
        values.put("first-items", record.firstItems());
        values.put("second-items", record.secondItems());
        return values;
    }

    private static String string(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry != null) {
                result.add(String.valueOf(entry));
            }
        }
        return result;
    }
}
