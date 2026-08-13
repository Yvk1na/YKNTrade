package cn.ykntrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyAmountTest {
    @Test
    void parsesSupportedSuffixesCaseInsensitively() {
        assertValue("1500", MoneyAmount.parse("1.5k"));
        assertValue("100000000", MoneyAmount.parse("100M"));
        assertValue("1300000000", MoneyAmount.parse("1.3B"));
        assertValue("2000000000000", MoneyAmount.parse("2t"));
    }

    @Test
    void parsesPlainAndGroupedNumbers() {
        assertValue("1234567", MoneyAmount.parse("1,234,567"));
        assertValue("0", MoneyAmount.parse("0"));
    }

    @Test
    void rejectsMalformedOrNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> MoneyAmount.parse("-1"));
        assertThrows(IllegalArgumentException.class, () -> MoneyAmount.parse("1Q"));
        assertThrows(IllegalArgumentException.class, () -> MoneyAmount.parse("NaN"));
    }

    @Test
    void formatsAmountsUsingCompactUnits() {
        assertEquals("300M", MoneyAmount.format(new BigDecimal("300000000")));
        assertEquals("1.3B", MoneyAmount.format(new BigDecimal("1300000000")));
        assertEquals("2T", MoneyAmount.format(new BigDecimal("2000000000000")));
    }

    private static void assertValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
