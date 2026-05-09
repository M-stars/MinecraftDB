package com.minecraftdb.storage;

import com.minecraftdb.util.ColorCode;
import net.minecraft.world.item.DyeColor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes Java data types into sequences of wool dye colors for world storage.
 */
public final class WoolEncoder {

    private WoolEncoder() {}

    // === NULL ===

    public static List<DyeColor> encodeNull() {
        return List.of(ColorCode.NULL_VAL);
    }

    // === INTEGER (32-bit) ===

    public static List<DyeColor> encodeInt(int value) {
        List<DyeColor> result = new ArrayList<>();
        if (value < 0) {
            result.add(ColorCode.NEGATIVE);
            value = -value;
        }
        String digits = String.valueOf(value);
        for (char c : digits.toCharArray()) {
            result.add(ColorCode.fromDigit(c - '0'));
        }
        if (result.isEmpty()) result.add(ColorCode.ZERO);
        return result;
    }

    // === BIGINT (64-bit) ===

    public static List<DyeColor> encodeBigInt(long value) {
        List<DyeColor> result = new ArrayList<>();
        if (value < 0) {
            result.add(ColorCode.NEGATIVE);
            value = -value;
        }
        String digits = String.valueOf(value);
        for (char c : digits.toCharArray()) {
            result.add(ColorCode.fromDigit(c - '0'));
        }
        if (result.isEmpty()) result.add(ColorCode.ZERO);
        return result;
    }

    // === BOOLEAN ===

    public static List<DyeColor> encodeBoolean(boolean value) {
        return List.of(value ? ColorCode.TRUE_BOOL : ColorCode.FALSE_BOOL);
    }

    // === FLOAT / DOUBLE ===

    public static List<DyeColor> encodeFloat(float value) {
        return encodeDecimal(String.valueOf(value));
    }

    public static List<DyeColor> encodeDouble(double value) {
        return encodeDecimal(String.valueOf(value));
    }

    private static List<DyeColor> encodeDecimal(String str) {
        List<DyeColor> result = new ArrayList<>();
        if (str.startsWith("-")) {
            result.add(ColorCode.NEGATIVE);
            str = str.substring(1);
        }
        for (char c : str.toCharArray()) {
            if (c == '.') {
                result.add(ColorCode.DECIMAL);
            } else if (c >= '0' && c <= '9') {
                result.add(ColorCode.fromDigit(c - '0'));
            }
        }
        return result;
    }

    // === VARCHAR / TEXT (String) — 使用Hex编码（每字节=2个4bit颜色，16色完美映射） ===

    public static List<DyeColor> encodeString(String value) {
        List<DyeColor> result = new ArrayList<>();
        result.add(ColorCode.STRING_MARKER);

        if (value == null || value.isEmpty()) {
            return result;
        }

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int high = (b >> 4) & 0x0F;  // 高4位 (0-15)
            int low = b & 0x0F;          // 低4位 (0-15)
            result.add(nibbleToColor(high));
            result.add(nibbleToColor(low));
        }
        return result;
    }

    /**
     * 将 0-15 的值映射到对应的羊毛颜色
     */
    private static DyeColor nibbleToColor(int nibble) {
        return switch (nibble) {
            case 0 -> ColorCode.ZERO;
            case 1 -> ColorCode.ONE;
            case 2 -> ColorCode.TWO;
            case 3 -> ColorCode.THREE;
            case 4 -> ColorCode.FOUR;
            case 5 -> ColorCode.FIVE;
            case 6 -> ColorCode.SIX;
            case 7 -> ColorCode.SEVEN;
            case 8 -> ColorCode.EIGHT;
            case 9 -> ColorCode.NINE;
            case 10 -> ColorCode.DECIMAL;     // A
            case 11 -> ColorCode.NEGATIVE;    // B
            case 12 -> ColorCode.STRING_MARKER; // C (within data: uses BROWN)
            case 13 -> ColorCode.TRUE_BOOL;   // D
            case 14 -> ColorCode.FALSE_BOOL;  // E
            case 15 -> ColorCode.NULL_VAL;    // F
            default -> ColorCode.ZERO;
        };
    }

    // === DATE / DATETIME (stored as BIGINT timestamp) ===

    public static List<DyeColor> encodeTimestamp(long epochMillis) {
        return encodeBigInt(epochMillis);
    }
}
