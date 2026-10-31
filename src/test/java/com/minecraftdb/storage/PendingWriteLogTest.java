package com.minecraftdb.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 待应用方块写入日志：FIFO 排水、读穿覆盖、事务清除（纯数据结构） */
class PendingWriteLogTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // DyeColor 静态初始化依赖原版注册表，纯 JUnit 环境需先引导（同 WoolCodecTest）
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }
    }

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    @Test
    @DisplayName("FIFO 排水：按插入序应用，budget 限制条数")
    void fifoDrain() {
        PendingWriteLog log = new PendingWriteLog();
        log.add(1, pos(1, 1, 1), DyeColor.RED);
        log.add(1, pos(1, 2, 1), DyeColor.BLUE);
        log.add(2, pos(1, 3, 1), null);

        List<String> order = new ArrayList<>();
        int applied = log.drain(2, (p, c) -> order.add(p.getY() + ":" + (c == null ? "AIR" : c.name())));
        assertEquals(2, applied);
        assertEquals(List.of("1:RED", "2:BLUE"), order);
        assertEquals(1, log.size());

        applied = log.drain(10, (p, c) -> order.add(p.getY() + ":" + (c == null ? "AIR" : c.name())));
        assertEquals(1, applied);
        assertEquals(0, log.size());
    }

    @Test
    @DisplayName("budget<=0 表示全部应用")
    void drainAll() {
        PendingWriteLog log = new PendingWriteLog();
        for (int i = 0; i < 5; i++) {
            log.add(1, pos(0, i, 0), DyeColor.WHITE);
        }
        int[] count = {0};
        log.drain(0, (p, c) -> count[0]++);
        assertEquals(5, count[0]);
        assertTrue(log.isEmpty());
    }

    @Test
    @DisplayName("同 pos 重复写入：值更新，读穿覆盖返回最新值")
    void overwriteSamePos() {
        PendingWriteLog log = new PendingWriteLog();
        log.add(1, pos(3, 3, 3), DyeColor.RED);
        log.add(1, pos(3, 3, 3), DyeColor.BLUE);
        assertEquals(1, log.size());
        assertEquals(DyeColor.BLUE, log.pendingColor(pos(3, 3, 3)));
        assertTrue(log.hasPending(pos(3, 3, 3)));
        assertFalse(log.hasPending(pos(9, 9, 9)));
        assertNull(log.pendingColor(pos(9, 9, 9)));
    }

    @Test
    @DisplayName("pendingColor 返回 null 表示待应用清除（空气），与无待应用写入区分靠 hasPending")
    void clearSemantics() {
        PendingWriteLog log = new PendingWriteLog();
        log.add(1, pos(1, 1, 1), null);
        assertTrue(log.hasPending(pos(1, 1, 1)));
        assertNull(log.pendingColor(pos(1, 1, 1)));
    }

    @Test
    @DisplayName("purgeTxn 只清除指定事务的待应用写入")
    void purgeTxn() {
        PendingWriteLog log = new PendingWriteLog();
        log.add(1, pos(1, 1, 1), DyeColor.RED);
        log.add(2, pos(2, 2, 2), DyeColor.BLUE);
        log.add(1, pos(3, 3, 3), DyeColor.GREEN);
        log.add(2, pos(4, 4, 4), DyeColor.YELLOW);

        int removed = log.purgeTxn(1);
        assertEquals(2, removed);
        assertFalse(log.hasPending(pos(1, 1, 1)));
        assertFalse(log.hasPending(pos(3, 3, 3)));
        assertTrue(log.hasPending(pos(2, 2, 2)));
        assertTrue(log.hasPending(pos(4, 4, 4)));
        assertEquals(2, log.size());

        // 清除后 FIFO 顺序保持
        List<Integer> ys = new ArrayList<>();
        log.drain(0, (p, c) -> ys.add(p.getY()));
        assertEquals(List.of(2, 4), ys);
    }

    @Test
    @DisplayName("清除后 FIFO 顺序保持")
    void purgeKeepsOrder() {
        PendingWriteLog log = new PendingWriteLog();
        log.add(1, pos(0, 1, 0), DyeColor.RED);
        log.add(2, pos(0, 2, 0), DyeColor.BLUE);
        log.add(1, pos(0, 3, 0), DyeColor.GREEN);
        log.purgeTxn(1);
        List<Integer> ys = new ArrayList<>();
        log.drain(0, (p, c) -> ys.add(p.getY()));
        assertEquals(List.of(2), ys);
    }

    @Test
    @DisplayName("clear 清空全部")
    void clearAll() {
        PendingWriteLog log = new PendingWriteLog();
        log.add(1, pos(1, 1, 1), DyeColor.RED);
        log.add(-1, pos(2, 2, 2), null);
        log.clear();
        assertTrue(log.isEmpty());
        assertEquals(0, log.drain(0, (p, c) -> {}));
    }
}
