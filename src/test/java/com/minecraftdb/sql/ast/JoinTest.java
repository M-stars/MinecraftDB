package com.minecraftdb.sql.ast;

import com.minecraftdb.sql.JoinNamespace;
import com.minecraftdb.sql.SqlLexer;
import com.minecraftdb.sql.SqlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** JOIN 解析 + 组合行命名空间（纯逻辑） */
class JoinTest {

    private SelectStatement parse(String sql) {
        return (SelectStatement) new SqlParser(new SqlLexer(sql).tokenize()).parse();
    }

    // === 解析 ===

    @Test
    @DisplayName("解析 INNER JOIN ... ON")
    void innerJoin() {
        SelectStatement stmt = parse("SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id");
        assertTrue(stmt.isJoinQuery());
        assertEquals(1, stmt.joins.size());
        assertEquals("INNER", stmt.joins.get(0).type());
        assertEquals("orders", stmt.joins.get(0).table().tableName());
        assertEquals("o", stmt.joins.get(0).table().alias());
        assertEquals("u", stmt.mainTable.alias());
        assertNotNull(stmt.joins.get(0).on());
    }

    @Test
    @DisplayName("解析 LEFT [OUTER] JOIN")
    void leftJoin() {
        SelectStatement stmt = parse("SELECT * FROM a LEFT JOIN b ON a.id = b.id");
        assertEquals("LEFT", stmt.joins.get(0).type());

        stmt = parse("SELECT * FROM a LEFT OUTER JOIN b ON a.id = b.id");
        assertEquals("LEFT", stmt.joins.get(0).type());
    }

    @Test
    @DisplayName("解析 JOIN 关键字（省略 INNER）")
    void bareJoin() {
        SelectStatement stmt = parse("SELECT * FROM a JOIN b ON a.id = b.id");
        assertEquals("INNER", stmt.joins.get(0).type());
    }

    @Test
    @DisplayName("无别名时限定名 = 表名")
    void noAlias() {
        SelectStatement stmt = parse("SELECT users.name FROM users JOIN orders ON users.id = orders.user_id");
        assertNull(stmt.mainTable.alias());
        assertEquals("users", stmt.mainTable.qualifier());
    }

    @Test
    @DisplayName("无 JOIN 时 isJoinQuery 为 false")
    void singleTable() {
        assertFalse(parse("SELECT * FROM users").isJoinQuery());
    }

    @Test
    @DisplayName("JOIN 缺 ON 报错")
    void missingOn() {
        assertThrows(SqlParser.ParseException.class,
                () -> parse("SELECT * FROM a JOIN b"));
    }

    // === 组合行命名空间 ===

    private JoinNamespace ns() {
        return JoinNamespace.build(List.of(
                new JoinNamespace.TableContext("u", List.of("id", "name", "role")),
                new JoinNamespace.TableContext("o", List.of("id", "user_id", "total"))));
    }

    @Test
    @DisplayName("限定列全部注册；歧义裸列（两表的 id）不注册")
    void namespaceLayout() {
        JoinNamespace ns = ns();
        assertEquals("u.id", ns.resolve("u.id"));
        assertEquals("u.id", ns.resolve("U.ID")); // 大小写不敏感 → 规范显示名
        assertEquals("o.user_id", ns.resolve("o.user_id"));
        assertNull(ns.resolve("id")); // 歧义：u.id 与 o.id 同名
        assertNotNull(ns.resolve("name")); // 无歧义裸列
        assertNotNull(ns.resolve("total"));
    }

    @Test
    @DisplayName("组合行解析器：裸列转发到来源限定列")
    void combinedResolver() {
        JoinNamespace ns = ns();
        // 限定槽位序：u.id, u.name, u.role, o.id, o.user_id, o.total
        List<Object> values = Arrays.asList(1, "Alice", "admin", 100, 1, 250);
        var resolver = ns.resolverFor(values);
        assertEquals(1, resolver.value("u.id"));
        assertEquals("Alice", resolver.value("name")); // 裸列 → u.name
        assertEquals(250, resolver.value("o.total"));
        assertNull(resolver.value("id")); // 歧义
        assertNull(resolver.value("ghost"));
        assertTrue(resolver.exists("u.name"));
        assertFalse(resolver.exists("id"));
        assertFalse(resolver.exists("ghost"));
    }

    @Test
    @DisplayName("LEFT JOIN null 行：右表列全 null")
    void leftNullRow() {
        JoinNamespace ns = ns();
        List<Object> values = Arrays.asList(1, "Alice", "admin", null, null, null);
        var resolver = ns.resolverFor(values);
        assertNull(resolver.value("o.total"));
        assertEquals("Alice", resolver.value("u.name"));
    }

    @Test
    @DisplayName("findEqui：两种方向都能提取连接键")
    void findEqui() {
        JoinNamespace ns = ns();
        var on1 = whereClause("u.id = o.user_id");
        var key1 = ns.findEqui(on1, 1, 1);
        assertNotNull(key1);
        assertEquals("u.id", key1.leftName());
        assertEquals("user_id", key1.rightColumn());

        var on2 = whereClause("o.user_id = u.id");
        var key2 = ns.findEqui(on2, 1, 1);
        assertNotNull(key2);
        assertEquals("u.id", key2.leftName());
        assertEquals("user_id", key2.rightColumn());
    }

    @Test
    @DisplayName("findEqui：非等值/字面量操作数时返回 null")
    void findEquiNegative() {
        JoinNamespace ns = ns();
        assertNull(ns.findEqui(whereClause("u.id > o.user_id"), 1, 1)); // 非等值
        assertNull(ns.findEqui(whereClause("name = 'x'"), 1, 1));       // 字面量
        // u.id = o.total 也是合法等值键（o.total 属于右表）
        var key = ns.findEqui(whereClause("u.id = o.total"), 1, 1);
        assertNotNull(key);
        assertEquals("total", key.rightColumn());
    }

    @Test
    @DisplayName("AND 合取中的等值项可提取")
    void findEquiWithAnd() {
        JoinNamespace ns = ns();
        var key = ns.findEqui(whereClause("o.total > 10 AND u.id = o.user_id"), 1, 1);
        assertNotNull(key);
        assertEquals("u.id", key.leftName());
    }

    private WhereClause whereClause(String condition) {
        return parse("SELECT * FROM u JOIN o ON " + condition).joins.get(0).on();
    }
}
