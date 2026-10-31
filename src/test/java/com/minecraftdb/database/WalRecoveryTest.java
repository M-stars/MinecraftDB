package com.minecraftdb.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** WAL 恢复判定（纯函数）：已提交重放 / 未提交回滚 / 损坏保留 */
class WalRecoveryTest {

    private static String begin(int txn) {
        return "{\"type\":\"BEGIN\",\"txnId\":" + txn + "}";
    }

    private static String op(int txn, int x, int y, int z, String oldC, String newC) {
        return "{\"type\":\"OP\",\"txnId\":" + txn + ",\"table\":\"t\",\"x\":" + x + ",\"y\":" + y
                + ",\"z\":" + z + ",\"oldColor\":" + (oldC == null ? "null" : "\"" + oldC + "\"")
                + ",\"newColor\":" + (newC == null ? "null" : "\"" + newC + "\"") + "}";
    }

    private static String commit(int txn) {
        return "{\"type\":\"COMMIT\",\"txnId\":" + txn + "}";
    }

    @Test
    @DisplayName("已提交事务：committed=true，OP 按文件顺序重放")
    void committed() {
        WalRecovery.Plan plan = WalRecovery.analyze(List.of(
                begin(1), op(1, 1, 1, 1, null, "RED"), op(1, 2, 1, 1, "BLUE", null), commit(1)));
        assertTrue(plan.committed());
        assertFalse(plan.corrupt());
        assertEquals(2, plan.ops().size());
        assertEquals(1, plan.ops().get(0).x);
        assertEquals(2, plan.ops().get(1).x);
    }

    @Test
    @DisplayName("未提交事务：committed=false，OP 逆序供恢复")
    void uncommitted() {
        WalRecovery.Plan plan = WalRecovery.analyze(List.of(
                begin(1), op(1, 1, 1, 1, null, "RED"), op(1, 2, 1, 1, "BLUE", null)));
        assertFalse(plan.committed());
        assertFalse(plan.corrupt());
        assertEquals(2, plan.opsReversed().size());
        assertEquals(2, plan.opsReversed().get(0).x); // 逆序：后写的先恢复
        assertEquals(1, plan.opsReversed().get(1).x);
    }

    @Test
    @DisplayName("损坏行：corrupt=true，不做任何恢复")
    void corrupt() {
        WalRecovery.Plan plan = WalRecovery.analyze(List.of(
                begin(1), op(1, 1, 1, 1, null, "RED"), "{corrupted json!!!"));
        assertTrue(plan.corrupt());
        assertFalse(plan.committed());
    }

    @Test
    @DisplayName("JSON null 解析为 null 条目视为损坏")
    void nullEntry() {
        WalRecovery.Plan plan = WalRecovery.analyze(List.of(begin(1), "null"));
        assertTrue(plan.corrupt());
    }

    @Test
    @DisplayName("空行与 BEGIN 行被忽略")
    void blankAndBegin() {
        WalRecovery.Plan plan = WalRecovery.analyze(List.of("", "  ", begin(7), op(7, 3, 3, 3, null, "WHITE"), ""));
        assertFalse(plan.corrupt());
        assertFalse(plan.committed());
        assertEquals(1, plan.ops().size());
    }

    @Test
    @DisplayName("COMMIT 后的行不再读取")
    void stopsAtCommit() {
        WalRecovery.Plan plan = WalRecovery.analyze(List.of(
                begin(1), op(1, 1, 1, 1, null, "RED"), commit(1), op(1, 9, 9, 9, null, "BLACK")));
        assertTrue(plan.committed());
        assertEquals(1, plan.ops().size());
    }

    @Test
    @DisplayName("空 WAL（仅 BEGIN）→ 未提交且无操作")
    void emptyTxn() {
        WalRecovery.Plan plan = WalRecovery.analyze(List.of(begin(1)));
        assertFalse(plan.committed());
        assertFalse(plan.corrupt());
        assertTrue(plan.ops().isEmpty());
    }
}
