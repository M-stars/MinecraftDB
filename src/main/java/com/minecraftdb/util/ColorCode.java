package com.minecraftdb.util;

import net.minecraft.world.item.DyeColor;

/**
 * 16-color wool encoding scheme for data storage.
 * Each dye color maps to a specific data value.
 */
public final class ColorCode {

    public static final DyeColor ZERO         = DyeColor.WHITE;       // 0x0
    public static final DyeColor ONE          = DyeColor.ORANGE;      // 0x1
    public static final DyeColor TWO          = DyeColor.MAGENTA;     // 0x2
    public static final DyeColor THREE        = DyeColor.LIGHT_BLUE;  // 0x3
    public static final DyeColor FOUR         = DyeColor.YELLOW;      // 0x4
    public static final DyeColor FIVE         = DyeColor.LIME;        // 0x5
    public static final DyeColor SIX          = DyeColor.PINK;        // 0x6
    public static final DyeColor SEVEN        = DyeColor.GRAY;        // 0x7
    public static final DyeColor EIGHT        = DyeColor.LIGHT_GRAY;  // 0x8
    public static final DyeColor NINE         = DyeColor.CYAN;        // 0x9
    public static final DyeColor DECIMAL      = DyeColor.PURPLE;      // 0xA
    public static final DyeColor NEGATIVE     = DyeColor.BLUE;        // 0xB
    public static final DyeColor STRING_MARKER = DyeColor.BROWN;      // 0xC
    public static final DyeColor TRUE_BOOL    = DyeColor.GREEN;       // 0xD
    public static final DyeColor FALSE_BOOL   = DyeColor.RED;         // 0xE
    public static final DyeColor NULL_VAL     = DyeColor.BLACK;       // 0xF

    private static final DyeColor[] DIGIT_TO_COLOR = {
        ZERO, ONE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE
    };

    private ColorCode() {}

    public static DyeColor fromDigit(int digit) {
        if (digit < 0 || digit > 9) throw new IllegalArgumentException("Digit must be 0-9, got: " + digit);
        return DIGIT_TO_COLOR[digit];
    }

    public static int toDigit(DyeColor color) {
        for (int i = 0; i < DIGIT_TO_COLOR.length; i++) {
            if (DIGIT_TO_COLOR[i] == color) return i;
        }
        return -1;
    }

    public static boolean isDigit(DyeColor color) {
        return toDigit(color) >= 0;
    }

    public static boolean isSpecial(DyeColor color) {
        return color == DECIMAL || color == NEGATIVE || color == STRING_MARKER
            || color == TRUE_BOOL || color == FALSE_BOOL || color == NULL_VAL;
    }
}
