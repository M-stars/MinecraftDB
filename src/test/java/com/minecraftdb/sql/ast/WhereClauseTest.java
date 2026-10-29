package com.minecraftdb.sql.ast;

import com.minecraftdb.sql.SqlLexer;
import com.minecraftdb.sql.SqlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WhereClauseTest {

    private WhereClause where(String condition) {
        SqlLexer lexer = new SqlLexer("SELECT * FROM t WHERE " + condition);
        return ((SelectStatement) new SqlParser(lexer.tokenize()).parse()).whereClause;
    }

    private static final List<String> COLS = List.of("id", "name", "age");

    @Test
    @DisplayName("等于条件")
    void equalsMatch() {
        assertTrue(where("name = 'Alice'").evaluate(COLS, List.of(1, "Alice", 25)));
        assertFalse(where("name = 'Bob'").evaluate(COLS, List.of(1, "Alice", 25)));
    }

    @Test
    @DisplayName("AND/OR 优先级：AND 先于 OR（a OR b AND c == a OR (b AND c)）")
    void precedence() {
        WhereClause wc = where("id = 1 OR name = 'x' AND age = 3");
        // id=1 命中 → true，无论后件
        assertTrue(wc.evaluate(COLS, List.of(1, "anything", 0)));
        // id≠1，(name='x' AND age=3) 不成立 → false
        assertFalse(wc.evaluate(COLS, List.of(2, "x", 0)));
        assertTrue(wc.evaluate(COLS, List.of(2, "x", 3)));
    }

    @Test
    @DisplayName("括号改变优先级")
    void parentheses() {
        WhereClause wc = where("(id = 1 OR name = 'x') AND age = 3");
        assertFalse(wc.evaluate(COLS, List.of(1, "anything", 0)));
        assertFalse(wc.evaluate(COLS, List.of(2, "x", 0)));
        assertTrue(wc.evaluate(COLS, List.of(1, "anything", 3)));
    }

    @Test
    @DisplayName("NOT 前缀")
    void notPrefix() {
        assertTrue(where("NOT id = 1").evaluate(COLS, List.of(2, "a", 3)));
        assertFalse(where("NOT id = 1").evaluate(COLS, List.of(1, "a", 3)));
        assertTrue(where("NOT (id = 1 AND age = 3)").evaluate(COLS, List.of(1, "a", 4)));
    }

    @Test
    @DisplayName("IS NULL / IS NOT NULL")
    void isNull() {
        assertTrue(where("name IS NULL").evaluate(COLS, Arrays.asList(1, null, 3)));
        assertFalse(where("name IS NULL").evaluate(COLS, List.of(1, "a", 3)));
        assertTrue(where("name IS NOT NULL").evaluate(COLS, List.of(1, "a", 3)));
        assertFalse(where("name IS NOT NULL").evaluate(COLS, Arrays.asList(1, null, 3)));
    }

    @Test
    @DisplayName("IN / NOT IN")
    void inList() {
        assertTrue(where("id IN (1, 2, 3)").evaluate(COLS, List.of(2, "a", 3)));
        assertFalse(where("id IN (1, 2, 3)").evaluate(COLS, List.of(9, "a", 3)));
        assertFalse(where("id NOT IN (1, 2, 3)").evaluate(COLS, List.of(2, "a", 3)));
        assertTrue(where("id NOT IN (1, 2, 3)").evaluate(COLS, List.of(9, "a", 3)));
        assertTrue(where("name IN ('Alice', 'Bob')").evaluate(COLS, List.of(1, "Alice", 3)));
    }

    @Test
    @DisplayName("LIKE：通配符 % 与 _")
    void like() {
        assertTrue(where("name LIKE 'Ali%'").evaluate(COLS, List.of(1, "Alice", 3)));
        assertFalse(where("name LIKE 'ali%'").evaluate(COLS, List.of(1, "Alice", 3)));
        assertTrue(where("name LIKE '_ob'").evaluate(COLS, List.of(1, "Bob", 3)));
        assertTrue(where("name LIKE '%li%'").evaluate(COLS, List.of(1, "Alice", 3)));
        assertFalse(where("name LIKE 'ob'").evaluate(COLS, List.of(1, "Bob", 3)));
        assertTrue(where("name LIKE 'Alice'").evaluate(COLS, List.of(1, "Alice", 3)));
    }

    @Test
    @DisplayName("NULL 参与 IN/LIKE/比较恒为 false（unknown 语义）")
    void nullSemantics() {
        assertFalse(where("name IN ('Alice')").evaluate(COLS, Arrays.asList(1, null, 3)));
        assertFalse(where("name NOT IN ('Alice')").evaluate(COLS, Arrays.asList(1, null, 3)));
        assertFalse(where("name LIKE '%i%'").evaluate(COLS, Arrays.asList(1, null, 3)));
        assertFalse(where("name = 'x'").evaluate(COLS, Arrays.asList(1, null, 3)));
        assertFalse(where("age > 1").evaluate(COLS, Arrays.asList(1, "a", null)));
    }

    @Test
    @DisplayName("引用不存在的列恒为 false")
    void unknownColumn() {
        assertFalse(where("ghost = 1").evaluate(COLS, List.of(1, "a", 3)));
        assertFalse(where("ghost IS NULL").evaluate(COLS, List.of(1, "a", 3)));
    }

    @Test
    @DisplayName("大小写不敏感的列名")
    void caseInsensitiveColumn() {
        assertTrue(where("NAME = 'Alice'").evaluate(COLS, List.of(1, "Alice", 3)));
        assertTrue(where("Id = 1").evaluate(COLS, List.of(1, "Alice", 3)));
    }

    @Test
    @DisplayName("复合条件组合")
    void combined() {
        WhereClause wc = where("age >= 18 AND (name LIKE 'A%' OR id IN (7, 9)) AND name IS NOT NULL");
        assertTrue(wc.evaluate(COLS, List.of(1, "Alice", 25)));
        assertFalse(wc.evaluate(COLS, List.of(1, "Alice", 17)));
        assertTrue(wc.evaluate(COLS, List.of(9, "Bob", 20)));
        assertFalse(wc.evaluate(COLS, List.of(9, "Bob", 17)));
        assertFalse(wc.evaluate(COLS, Arrays.asList(3, null, 30)));
    }
}
