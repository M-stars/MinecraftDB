package com.minecraftdb.sql.ast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WhereClauseTest {

    @Test
    @DisplayName("等于条件匹配")
    void equalsMatch() {
        WhereClause wc = new WhereClause();
        wc.addCondition(new WhereClause.Condition("name", "=", "'Alice'"));
        List<String> cols = List.of("id", "name", "age");
        List<Object> row = List.of(1, "Alice", 25);
        assertTrue(wc.evaluate(cols, row));
    }

    @Test
    @DisplayName("等于条件不匹配")
    void equalsNoMatch() {
        WhereClause wc = new WhereClause();
        wc.addCondition(new WhereClause.Condition("name", "=", "'Bob'"));
        List<String> cols = List.of("name");
        List<Object> row = List.of("Alice");
        assertFalse(wc.evaluate(cols, row));
    }

    @Test
    @DisplayName("大于条件")
    void greaterThan() {
        WhereClause wc = new WhereClause();
        wc.addCondition(new WhereClause.Condition("age", ">", "18"));
        List<String> cols = List.of("age");
        assertTrue(wc.evaluate(cols, List.of(25)));
        assertFalse(wc.evaluate(cols, List.of(15)));
        assertFalse(wc.evaluate(cols, List.of(18)));
    }

    @Test
    @DisplayName("小于条件")
    void lessThan() {
        WhereClause wc = new WhereClause();
        wc.addCondition(new WhereClause.Condition("age", "<", "60"));
        List<String> cols = List.of("age");
        assertTrue(wc.evaluate(cols, List.of(25)));
        assertFalse(wc.evaluate(cols, List.of(80)));
    }

    @Test
    @DisplayName("不等于条件")
    void notEquals() {
        WhereClause wc = new WhereClause();
        wc.addCondition(new WhereClause.Condition("status", "!=", "'deleted'"));
        List<String> cols = List.of("status");
        assertTrue(wc.evaluate(cols, List.of("active")));
        assertFalse(wc.evaluate(cols, List.of("deleted")));
    }

    @Test
    @DisplayName("AND 组合条件")
    void andCondition() {
        WhereClause wc = new WhereClause();
        wc.addCondition(new WhereClause.Condition("age", ">", "18"));
        wc.addLogicalOp(WhereClause.LogicalOp.AND);
        wc.addCondition(new WhereClause.Condition("status", "=", "'active'"));
        List<String> cols = List.of("age", "status");
        assertTrue(wc.evaluate(cols, List.of(25, "active")));
        assertFalse(wc.evaluate(cols, List.of(25, "inactive")));
        assertFalse(wc.evaluate(cols, List.of(10, "active")));
    }

    @Test
    @DisplayName("OR 组合条件")
    void orCondition() {
        WhereClause wc = new WhereClause();
        wc.addCondition(new WhereClause.Condition("age", ">", "60"));
        wc.addLogicalOp(WhereClause.LogicalOp.OR);
        wc.addCondition(new WhereClause.Condition("age", "<", "18"));
        List<String> cols = List.of("age");
        assertTrue(wc.evaluate(cols, List.of(70)));
        assertTrue(wc.evaluate(cols, List.of(10)));
        assertFalse(wc.evaluate(cols, List.of(30)));
    }

    @Test
    @DisplayName("空 WHERE 子句始终为 true")
    void emptyWhereAlwaysTrue() {
        WhereClause wc = new WhereClause();
        assertTrue(wc.evaluate(List.of("x"), List.of(1)));
    }

    @Test
    @DisplayName("NULL 值比较")
    void nullValueComparison() {
        WhereClause wc = new WhereClause();
        wc.addCondition(new WhereClause.Condition("col", "=", "null"));
        List<String> cols = List.of("col");
        // NULL 值在列表中会被表示为 null
        assertFalse(wc.evaluate(cols, List.of("something")));
    }

    @Test
    @DisplayName("列名不存在时返回 false")
    void missingColumn() {
        WhereClause wc = new WhereClause();
        wc.addCondition(new WhereClause.Condition("nonexistent", "=", "'x'"));
        assertFalse(wc.evaluate(List.of("name"), List.of("Alice")));
    }
}
