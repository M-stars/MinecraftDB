package com.minecraftdb.sql.ast;

import com.minecraftdb.sql.SqlLexer;
import com.minecraftdb.sql.SqlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Expression AST 求值 + WHERE 表达式操作数 + BETWEEN + 标量函数。
 */
class ExpressionTest {

    /** 简易行解析器：按 map 取值（大小写不敏感） */
    private static Expression.Resolver resolverOf(Map<String, Object> row) {
        Map<String, Object> lower = new HashMap<>();
        row.forEach((k, v) -> lower.put(k.toLowerCase(), v));
        return new Expression.Resolver() {
            @Override
            public Object value(String name) {
                return lower.get(name.toLowerCase());
            }

            @Override
            public boolean exists(String name) {
                return lower.containsKey(name.toLowerCase());
            }
        };
    }

    /** null 值容错的行构造（Map.of 不接受 null） */
    private static Map<String, Object> rowOf(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Expression expr(String sql) {
        // 借 SELECT 投影解析表达式
        var stmt = (SelectStatement) new SqlParser(
                new SqlLexer("SELECT " + sql + " FROM t").tokenize()).parse();
        return stmt.selectItems.get(0).expr();
    }

    private static final List<String> COLS = List.of("id", "name", "age", "score");

    private WhereClause where(String condition) {
        return ((SelectStatement) new SqlParser(
                new SqlLexer("SELECT * FROM t WHERE " + condition).tokenize()).parse()).whereClause;
    }

    /** 数值断言：不关心 Long/Double 装箱类型 */
    private static void assertNum(Object actual, double expected) {
        assertInstanceOf(Number.class, actual);
        assertEquals(expected, ((Number) actual).doubleValue(), 1e-9);
    }

    // === 表达式求值 ===

    @Test
    @DisplayName("算术运算与优先级：乘除先于加减")
    void arithmetic() {
        assertNum(expr("1 + 2 * 3").eval(resolverOf(Map.of())), 7.0);
        assertNum(expr("(1 + 2) * 3").eval(resolverOf(Map.of())), 9.0);
        assertNum(expr("5 / 2").eval(resolverOf(Map.of())), 2.5);
        assertNum(expr("5 % 2").eval(resolverOf(Map.of())), 1.0);
        assertNum(expr("-(1 + 2)").eval(resolverOf(Map.of())), -3.0);
    }

    @Test
    @DisplayName("除零与 NULL 传播：结果为 null")
    void nullPropagation() {
        assertNull(expr("1 / 0").eval(resolverOf(Map.of())));
        assertNull(expr("1 + NULL").eval(resolverOf(Map.of())));
        assertNull(expr("NULL % 2").eval(resolverOf(Map.of())));
    }

    @Test
    @DisplayName("列引用参与运算")
    void columnArithmetic() {
        Expression e = expr("age * 2 + 1");
        assertNum(e.eval(resolverOf(Map.of("age", 25))), 51.0);
        assertNull(e.eval(resolverOf(rowOf("age", null))));
    }

    // === 标量函数 ===

    @Test
    @DisplayName("标量函数：UPPER/LOWER/LENGTH/TRIM")
    void stringFunctions() {
        assertEquals("ALICE", expr("UPPER(name)").eval(resolverOf(Map.of("name", "Alice"))));
        assertEquals("alice", expr("LOWER(name)").eval(resolverOf(Map.of("name", "Alice"))));
        assertEquals(5L, expr("LENGTH(name)").eval(resolverOf(Map.of("name", "Alice"))));
        assertEquals("ab", expr("TRIM(name)").eval(resolverOf(Map.of("name", " ab "))));
        assertNull(expr("UPPER(name)").eval(resolverOf(rowOf("name", null))));
    }

    @Test
    @DisplayName("标量函数：ABS/ROUND/COALESCE")
    void numericFunctions() {
        assertNum(expr("ABS(-3)").eval(resolverOf(Map.of())), 3.0);
        assertNum(expr("ROUND(score, 2)").eval(resolverOf(Map.of("score", 3.14159))), 3.14);
        assertNum(expr("ROUND(score)").eval(resolverOf(Map.of("score", 3.6))), 4.0);
        assertEquals("d", expr("COALESCE(NULL, NULL, 'd')").eval(resolverOf(Map.of())));
        assertNull(expr("COALESCE(a, b)").eval(resolverOf(rowOf("a", null, "b", null))));
    }

    @Test
    @DisplayName("嵌套函数：UPPER(SUBSTRING 不可用) → LENGTH(UPPER(x))")
    void nestedFunctions() {
        assertEquals(5L, expr("LENGTH(UPPER(name))").eval(resolverOf(Map.of("name", "Alice"))));
    }

    // === WHERE 表达式操作数 ===

    @Test
    @DisplayName("WHERE 两侧列比较与表达式")
    void whereExpressions() {
        assertTrue(where("age > id * 10").evaluate(COLS, List.of(2, "a", 30, 1.0)));
        assertFalse(where("age > id * 10").evaluate(COLS, List.of(2, "a", 15, 1.0)));
        assertTrue(where("age + 1 = 26").evaluate(COLS, List.of(1, "a", 25, 0.0)));
        // 右侧列引用
        assertTrue(where("id = age - 24").evaluate(COLS, List.of(1, "a", 25, 0.0)));
    }

    @Test
    @DisplayName("WHERE 中使用标量函数")
    void whereFunctions() {
        assertTrue(where("UPPER(name) = 'ALICE'").evaluate(COLS, List.of(1, "Alice", 25, 0.0)));
        assertTrue(where("LENGTH(name) > 3").evaluate(COLS, List.of(1, "Alice", 25, 0.0)));
        assertFalse(where("LENGTH(name) > 3").evaluate(COLS, List.of(1, "Bob", 25, 0.0)));
    }

    // === BETWEEN ===

    @Test
    @DisplayName("BETWEEN 双闭区间")
    void between() {
        assertTrue(where("age BETWEEN 18 AND 30").evaluate(COLS, List.of(1, "a", 18, 0.0)));
        assertTrue(where("age BETWEEN 18 AND 30").evaluate(COLS, List.of(1, "a", 30, 0.0)));
        assertTrue(where("age BETWEEN 18 AND 30").evaluate(COLS, List.of(1, "a", 25, 0.0)));
        assertFalse(where("age BETWEEN 18 AND 30").evaluate(COLS, List.of(1, "a", 31, 0.0)));
        assertFalse(where("age BETWEEN 18 AND 30").evaluate(COLS, List.of(1, "a", 17, 0.0)));
    }

    @Test
    @DisplayName("NOT BETWEEN")
    void notBetween() {
        assertTrue(where("age NOT BETWEEN 18 AND 30").evaluate(COLS, List.of(1, "a", 31, 0.0)));
        assertFalse(where("age NOT BETWEEN 18 AND 30").evaluate(COLS, List.of(1, "a", 25, 0.0)));
    }

    @Test
    @DisplayName("BETWEEN 与 AND 的结合：条件自身的 AND 先消耗")
    void betweenWithAnd() {
        // age BETWEEN 18 AND 30 AND id = 1 → (age BETWEEN 18 AND 30) AND (id = 1)
        WhereClause wc = where("age BETWEEN 18 AND 30 AND id = 1");
        assertTrue(wc.evaluate(COLS, List.of(1, "a", 25, 0.0)));
        assertFalse(wc.evaluate(COLS, List.of(2, "a", 25, 0.0)));
        assertFalse(wc.evaluate(COLS, List.of(1, "a", 40, 0.0)));
    }

    @Test
    @DisplayName("BETWEEN NULL 语义：恒 false")
    void betweenNull() {
        assertFalse(where("age BETWEEN 1 AND 2").evaluate(COLS, java.util.Arrays.asList(1, "a", null, 0.0)));
        assertFalse(where("age NOT BETWEEN 1 AND 2").evaluate(COLS, java.util.Arrays.asList(1, "a", null, 0.0)));
    }

    @Test
    @DisplayName("数值与字符串字面量的数值比较")
    void numericCompare() {
        assertTrue(where("score = 3.14").evaluate(COLS, List.of(1, "a", 1, 3.14)));
        assertTrue(where("age = 25").evaluate(COLS, List.of(1, "a", 25, 0.0)));
        // 旧语义保留：字符串比较大小写敏感
        assertFalse(where("name = 'alice'").evaluate(COLS, List.of(1, "Alice", 1, 0.0)));
    }

    @Test
    @DisplayName("IS NULL 语义：未知列恒 false，存在但为 NULL 为 true")
    void isNullSemantics() {
        assertTrue(where("name IS NULL").evaluate(COLS, java.util.Arrays.asList(1, null, 3, 0.0)));
        assertFalse(where("name IS NULL").evaluate(COLS, List.of(1, "a", 3, 0.0)));
        assertFalse(where("ghost IS NULL").evaluate(COLS, List.of(1, "a", 3, 0.0)));
        assertFalse(where("ghost IS NOT NULL").evaluate(COLS, List.of(1, "a", 3, 0.0)));
    }
}
