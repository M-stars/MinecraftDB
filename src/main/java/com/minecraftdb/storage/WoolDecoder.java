package com.minecraftdb.storage;

import com.minecraftdb.util.ColorCode;
import net.minecraft.world.item.DyeColor;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Decodes wool dye color sequences back into Java data types.
 */
public final class WoolDecoder {

    private WoolDecoder() {}

    public static Integer decodeInt(List<DyeColor> colors) {
        if (colors == null || colors.isEmpty()) return null;
        if (colors.get(0) == ColorCode.NULL_VAL) return null;

        boolean negative = false;
        int idx = 0;
        if (colors.get(0) == ColorCode.NEGATIVE) {
            negative = true;
            idx = 1;
        }

        StringBuilder digits = new StringBuilder();
        for (; idx < colors.size(); idx++) {
            int digit = ColorCode.toDigit(colors.get(idx));
            if (digit >= 0) {
                digits.append(digit);
            }
        }

        if (digits.isEmpty()) return 0;
        try {
            long val = Long.parseLong(digits.toString());
            long signed = negative ? -val : val;
            if (signed < Integer.MIN_VALUE || signed > Integer.MAX_VALUE) return null;
            return (int) signed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Long decodeBigInt(List<DyeColor> colors) {
        if (colors == null || colors.isEmpty()) return null;
        if (colors.get(0) == ColorCode.NULL_VAL) return null;

        boolean negative = false;
        int idx = 0;
        if (colors.get(0) == ColorCode.NEGATIVE) {
            negative = true;
            idx = 1;
        }

        StringBuilder digits = new StringBuilder();
        for (; idx < colors.size(); idx++) {
            int digit = ColorCode.toDigit(colors.get(idx));
            if (digit >= 0) {
                digits.append(digit);
            }
        }

        if (digits.isEmpty()) return 0L;
        try {
            long val = Long.parseLong(digits.toString());
            return negative ? -val : val;
        } catch (NumberFormatException e) {
            // Long.MIN_VALUE 的量级 9223372036854775808 超出 long 正数表示范围
            if (negative && "9223372036854775808".contentEquals(digits)) {
                return Long.MIN_VALUE;
            }
            return null;
        }
    }

    public static Boolean decodeBoolean(DyeColor color) {
        if (color == ColorCode.TRUE_BOOL) return true;
        if (color == ColorCode.FALSE_BOOL) return false;
        if (color == ColorCode.NULL_VAL) return null;
        return null;
    }

    public static Float decodeFloat(List<DyeColor> colors) {
        Double d = decodeDouble(colors);
        return d != null ? d.floatValue() : null;
    }

    public static Double decodeDouble(List<DyeColor> colors) {
        if (colors == null || colors.isEmpty()) return null;
        if (colors.get(0) == ColorCode.NULL_VAL) return null;

        boolean negative = false;
        int idx = 0;
        if (colors.get(0) == ColorCode.NEGATIVE) {
            negative = true;
            idx = 1;
        }

        StringBuilder numStr = new StringBuilder();
        for (; idx < colors.size(); idx++) {
            DyeColor c = colors.get(idx);
            if (c == ColorCode.DECIMAL) {
                numStr.append('.');
            } else {
                int digit = ColorCode.toDigit(c);
                if (digit >= 0) {
                    numStr.append(digit);
                }
            }
        }

        if (numStr.isEmpty()) return 0.0;
        try {
            double val = Double.parseDouble(numStr.toString());
            return negative ? -val : val;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String decodeString(List<DyeColor> colors) {
        if (colors == null || colors.isEmpty()) return null;
        if (colors.get(0) == ColorCode.NULL_VAL) return null;

        int startIdx = 0;
        if (colors.get(0) == ColorCode.STRING_MARKER) {
            startIdx = 1;
        }

        // Hex解码：每2个颜色 = 1个字节
        int colorCount = colors.size() - startIdx;
        if (colorCount == 0) return "";

        byte[] bytes = new byte[colorCount / 2];
        int byteIdx = 0;
        for (int i = startIdx; i < colors.size() - 1; i += 2) {
            int high = colorToNibble(colors.get(i));
            int low = colorToNibble(colors.get(i + 1));
            bytes[byteIdx++] = (byte) ((high << 4) | low);
        }

        // 如果有奇数个颜色，最后一个单独处理
        if (colorCount % 2 != 0) {
            byte[] trimmed = new byte[bytes.length];
            System.arraycopy(bytes, 0, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 将羊毛颜色反向映射为 0-15 的 nibble 值
     */
    private static int colorToNibble(DyeColor color) {
        if (color == ColorCode.ZERO) return 0;
        if (color == ColorCode.ONE) return 1;
        if (color == ColorCode.TWO) return 2;
        if (color == ColorCode.THREE) return 3;
        if (color == ColorCode.FOUR) return 4;
        if (color == ColorCode.FIVE) return 5;
        if (color == ColorCode.SIX) return 6;
        if (color == ColorCode.SEVEN) return 7;
        if (color == ColorCode.EIGHT) return 8;
        if (color == ColorCode.NINE) return 9;
        if (color == ColorCode.DECIMAL) return 10;      // A
        if (color == ColorCode.NEGATIVE) return 11;     // B
        if (color == ColorCode.STRING_MARKER) return 12; // C
        if (color == ColorCode.TRUE_BOOL) return 13;    // D
        if (color == ColorCode.FALSE_BOOL) return 14;   // E
        if (color == ColorCode.NULL_VAL) return 15;     // F
        return 0;
    }

    public static Long decodeTimestamp(List<DyeColor> colors) {
        return decodeBigInt(colors);
    }
}
