package com.minecraftdb.storage;

import com.minecraftdb.util.ColorCode;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 羊毛颜色编码/解码 round-trip 测试。
 * 历史教训：白色羊毛曾同时充当数字 0 与数据终止符，导致含 0 的数据读取即损坏——
 * 这组测试是编码层的回归防线。
 */
class WoolCodecTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // DyeColor 静态初始化依赖原版注册表。Forge 给 Bootstrap.bootStrap() 打了补丁，
        // 尾部会调用 NetworkHooks.init()，纯 JUnit 环境下必然抛错——
        // 但原版注册表在抛错前已完成初始化，吞掉异常后 BuiltInRegistries 的
        // "Not bootstrapped" 守卫（isBootStrapped 标志）也已满足。
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }
    }

    private static void assertIntRoundTrip(int value) {
        assertEquals(Integer.valueOf(value), WoolDecoder.decodeInt(WoolEncoder.encodeInt(value)),
                "INT round-trip 失败: " + value);
    }

    private static void assertBigIntRoundTrip(long value) {
        assertEquals(Long.valueOf(value), WoolDecoder.decodeBigInt(WoolEncoder.encodeBigInt(value)),
                "BIGINT round-trip 失败: " + value);
    }

    private static void assertStringRoundTrip(String value) {
        assertEquals(value, WoolDecoder.decodeString(WoolEncoder.encodeString(value)),
                "STRING round-trip 失败: '" + value + "'");
    }

    @Test
    @DisplayName("INT 往返：含数字 0 的值必须完整还原")
    void intRoundTrip() {
        assertIntRoundTrip(0);
        assertIntRoundTrip(1);
        assertIntRoundTrip(10);
        assertIntRoundTrip(100);
        assertIntRoundTrip(105);
        assertIntRoundTrip(10086);
        assertIntRoundTrip(-5);
        assertIntRoundTrip(-105);
        assertIntRoundTrip(Integer.MAX_VALUE);
        assertIntRoundTrip(Integer.MIN_VALUE);
    }

    @Test
    @DisplayName("BIGINT 往返：含数字 0 与边界值")
    void bigIntRoundTrip() {
        assertBigIntRoundTrip(0L);
        assertBigIntRoundTrip(10L);
        assertBigIntRoundTrip(100L);
        assertBigIntRoundTrip(-1L);
        assertBigIntRoundTrip(-100L);
        assertBigIntRoundTrip(Long.MAX_VALUE);
        assertBigIntRoundTrip(Long.MIN_VALUE);
    }

    @Test
    @DisplayName("STRING 往返：空格/数字0/UTF-8/控制字符")
    void stringRoundTrip() {
        String[] values = {"", "hello", "Hello World", "player1", "0", "100 分", "  padded  ",
                "你好，世界", "a0b0c0", "line\nbreak", "tab\tchar", "!@#$%^&*()"};
        for (String value : values) {
            assertStringRoundTrip(value);
        }
    }

    @Test
    @DisplayName("BOOLEAN 往返")
    void booleanRoundTrip() {
        assertEquals(Boolean.TRUE, WoolDecoder.decodeBoolean(WoolEncoder.encodeBoolean(true).get(0)));
        assertEquals(Boolean.FALSE, WoolDecoder.decodeBoolean(WoolEncoder.encodeBoolean(false).get(0)));
        assertNull(WoolDecoder.decodeBoolean(DyeColor.BLACK));
        assertNull(WoolDecoder.decodeBoolean(DyeColor.WHITE));
    }

    @Test
    @DisplayName("FLOAT/DOUBLE 往返：小数点后 0 不截断")
    void floatDoubleRoundTrip() {
        assertEquals(1.0f, WoolDecoder.decodeFloat(WoolEncoder.encodeFloat(1.0f)), 1e-6);
        assertEquals(1.05f, WoolDecoder.decodeFloat(WoolEncoder.encodeFloat(1.05f)), 1e-6);
        assertEquals(-3.14f, WoolDecoder.decodeFloat(WoolEncoder.encodeFloat(-3.14f)), 1e-6);
        assertEquals(0.5, WoolDecoder.decodeDouble(WoolEncoder.encodeDouble(0.5)), 1e-12);
        assertEquals(-0.25, WoolDecoder.decodeDouble(WoolEncoder.encodeDouble(-0.25)), 1e-12);
    }

    @Test
    @DisplayName("NULL 编码解码为 null")
    void nullRoundTrip() {
        List<DyeColor> encoded = WoolEncoder.encodeNull();
        assertEquals(DyeColor.BLACK, encoded.get(0));
        assertNull(WoolDecoder.decodeInt(encoded));
        assertNull(WoolDecoder.decodeBigInt(encoded));
        assertNull(WoolDecoder.decodeString(encoded));
        assertNull(WoolDecoder.decodeDouble(encoded));
    }

    @Test
    @DisplayName("时间戳即 BIGINT 往返")
    void timestampRoundTrip() {
        long now = 1_798_600_000_000L; // 含 0 的毫秒时间戳
        assertBigIntRoundTrip(now);
    }

    @Test
    @DisplayName("16 个颜色映射常量两两不同")
    void colorMappingDistinct() throws Exception {
        java.util.Set<DyeColor> distinct = new java.util.HashSet<>();
        for (java.lang.reflect.Field f : ColorCode.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == DyeColor.class) {
                assertTrue(distinct.add((DyeColor) f.get(null)), "重复颜色映射: " + f.getName());
            }
        }
        assertEquals(16, distinct.size());
    }

    @Test
    @DisplayName("字符串按 UTF-8 字节逐 nibble 编码：'019' → 30 31 39")
    void stringHexEncoding() {
        List<DyeColor> encoded = WoolEncoder.encodeString("019");
        assertEquals(List.of(
                DyeColor.BROWN,                       // STRING_MARKER
                DyeColor.LIGHT_BLUE, DyeColor.WHITE,  // 0x30 '0'
                DyeColor.LIGHT_BLUE, DyeColor.ORANGE, // 0x31 '1'
                DyeColor.LIGHT_BLUE, DyeColor.CYAN    // 0x39 '9'
        ), encoded);
    }
}
