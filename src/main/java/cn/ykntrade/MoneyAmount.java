package cn.ykntrade;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MoneyAmount {
    static final BigDecimal ZERO = BigDecimal.ZERO;
    static final BigDecimal ONE_HUNDRED_MILLION = new BigDecimal("100000000");
    static final BigDecimal ONE_BILLION = new BigDecimal("1000000000");

    private static final Pattern INPUT = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)([KMBT]?)$");
    private static final Map<String, BigDecimal> MULTIPLIERS = Map.of(
            "", BigDecimal.ONE,
            "K", new BigDecimal("1000"),
            "M", new BigDecimal("1000000"),
            "B", ONE_BILLION,
            "T", new BigDecimal("1000000000000")
    );

    private MoneyAmount() {
    }

    static BigDecimal parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("金额不能为空");
        }
        String normalized = input.trim().replace(",", "").replace(" ", "")
                .toUpperCase(Locale.ROOT);
        Matcher matcher = INPUT.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("请输入数字，可在末尾使用 K、M、B 或 T");
        }

        BigDecimal value = new BigDecimal(matcher.group(1)).multiply(MULTIPLIERS.get(matcher.group(2)));
        if (value.signum() < 0 || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException("金额超出可用范围");
        }
        return value.stripTrailingZeros();
    }

    static String format(BigDecimal amount) {
        BigDecimal absolute = amount.abs();
        BigDecimal trillion = MULTIPLIERS.get("T");
        BigDecimal million = MULTIPLIERS.get("M");
        BigDecimal thousand = MULTIPLIERS.get("K");
        if (absolute.compareTo(trillion) >= 0) {
            return compact(amount, trillion, "T");
        }
        if (absolute.compareTo(ONE_BILLION) >= 0) {
            return compact(amount, ONE_BILLION, "B");
        }
        if (absolute.compareTo(million) >= 0) {
            return compact(amount, million, "M");
        }
        if (absolute.compareTo(thousand) >= 0) {
            return compact(amount, thousand, "K");
        }
        return plain(amount);
    }

    static String plain(BigDecimal amount) {
        BigDecimal normalized = amount.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static String compact(BigDecimal amount, BigDecimal divisor, String suffix) {
        return amount.divide(divisor).stripTrailingZeros().toPlainString() + suffix;
    }
}
