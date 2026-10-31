package com.minecraftdb.sql.ast;

import com.minecraftdb.sql.SqlLexer;
import com.minecraftdb.sql.SqlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批次 1 新语法解析：HAVING、TRUNCATE、SET 表达式、SELECT 表达式投影。
 */
class NewSyntaxTest {

    private Statement parse(String sql) {
        return new SqlParser(new SqlLexer(sql).tokenize()).parse();
    }

    // === HAVING ===

    @Test
    @DisplayName("解析 HAVING（别名条件）")
    void havingWithAlias() {
        SelectStatement stmt = (SelectStatement) parse(
                "SELECT role, COUNT(*) AS cnt FROM users GROUP BY role HAVING cnt > 5");
        assertNotNull(stmt.havingClause);
        assertTrue(stmt.havingClause.evaluate(List.of("role", "cnt"), List.of("admin", 10L)));
        assertFalse(stmt.havingClause.evaluate(List.of("role", "cnt"), List.of("admin", 3L)));
    }

    @Test
    @DisplayName("解析 HAVING（聚合表达式条件）")
    void havingWithAggregate() {
        SelectStatement stmt = (SelectStatement) parse(
                "SELECT role FROM users GROUP BY role HAVING COUNT(*) >= 2");
        assertNotNull(stmt.havingClause);
        // 按输出列/聚合标签求值
        assertTrue(stmt.havingClause.evaluate(List.of("role", "COUNT(*)"), List.of("admin", 3L)));
        assertFalse(stmt.havingClause.evaluate(List.of("role", "COUNT(*)"), List.of("admin", 1L)));
    }

    @Test
    @DisplayName("无 HAVING 时 havingClause 为 null")
    void noHaving() {
        SelectStatement stmt = (SelectStatement) parse("SELECT role FROM users GROUP BY role");
        assertNull(stmt.havingClause);
    }

    // === TRUNCATE ===

    @Test
    @DisplayName("解析 TRUNCATE TABLE")
    void truncateTable() {
        TruncateStatement stmt = (TruncateStatement) parse("TRUNCATE TABLE users");
        assertEquals("users", stmt.tableName);
        assertEquals(Statement.Type.TRUNCATE_TABLE, stmt.getType());
    }

    @Test
    @DisplayName("解析 TRUNCATE（无 TABLE 关键字）")
    void truncateBare() {
        TruncateStatement stmt = (TruncateStatement) parse("TRUNCATE users;");
        assertEquals("users", stmt.tableName);
    }

    // === SET 表达式 ===

    @Test
    @DisplayName("SET col = col + 1 表达式")
    void setExpression() {
        UpdateStatement stmt = (UpdateStatement) parse("UPDATE users SET score = score + 10 WHERE id = 1");
        Expression e = stmt.assignments.get("score");
        assertEquals("score + 10", e.sqlText());
        // 求值：基于原值
        Expression.Resolver r = WhereClause.resolverOf(List.of("id", "score"), List.of(1, 90));
        Object v = e.eval(r);
        assertTrue(v instanceof Number n && n.doubleValue() == 100.0);
    }

    @Test
    @DisplayName("SET 函数表达式")
    void setFunction() {
        UpdateStatement stmt = (UpdateStatement) parse("UPDATE users SET name = UPPER(name), score = ROUND(score * 1.1)");
        assertEquals(2, stmt.assignments.size());
        Expression.Resolver r = WhereClause.resolverOf(List.of("name", "score"), List.of("alice", 90.0));
        assertEquals("ALICE", stmt.assignments.get("name").eval(r));
        Object v = stmt.assignments.get("score").eval(r);
        assertInstanceOf(Number.class, v);
        assertEquals(99.0, ((Number) v).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("SELECT 表达式投影与别名")
    void selectExpression() {
        SelectStatement stmt = (SelectStatement) parse("SELECT name, score * 2 AS double_score FROM t");
        assertEquals(2, stmt.selectItems.size());
        assertEquals("name", stmt.selectItems.get(0).label());
        assertEquals("double_score", stmt.selectItems.get(1).label());
        assertEquals("score * 2", stmt.selectItems.get(1).expr().sqlText());
    }

    @Test
    @DisplayName("SELECT 标量函数投影")
    void selectScalarFunction() {
        SelectStatement stmt = (SelectStatement) parse("SELECT UPPER(name) FROM t");
        assertEquals("UPPER(name)", stmt.selectItems.get(0).label());
        // 兼容视图：非聚合 → function() == null
        assertNull(stmt.selectItems.get(0).function());
    }

    @Test
    @DisplayName("聚合兼容视图：function()/column() 与旧结构一致")
    void aggregateCompatView() {
        SelectStatement stmt = (SelectStatement) parse("SELECT category, COUNT(*) FROM products GROUP BY category");
        assertNull(stmt.selectItems.get(0).function());
        assertEquals("category", stmt.selectItems.get(0).column());
        assertEquals("COUNT", stmt.selectItems.get(1).function());
        assertEquals("*", stmt.selectItems.get(1).column());
        assertEquals("COUNT(*)", stmt.selectItems.get(1).label());
    }

    @Test
    @DisplayName("SELECT * 解析")
    void selectStar() {
        SelectStatement stmt = (SelectStatement) parse("SELECT * FROM t");
        assertTrue(stmt.isSelectAll());
        assertEquals("*", stmt.selectItems.get(0).label());
    }

    @Test
    @DisplayName("未知函数报错")
    void unknownFunction() {
        assertThrows(SqlParser.ParseException.class,
                () -> parse("SELECT FOO(name) FROM t"));
    }

    @Test
    @DisplayName("SUM(*) 报错：* 仅允许在 COUNT 中")
    void starOnlyInCount() {
        assertThrows(SqlParser.ParseException.class,
                () -> parse("SELECT SUM(*) FROM t"));
    }

    // === 子查询 ===

    @Test
    @DisplayName("IN 子查询解析")
    void inSubquery() {
        SelectStatement stmt = (SelectStatement) parse(
                "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)");
        WhereClause.Node root = stmt.whereClause.getRoot();
        assertInstanceOf(WhereClause.InSubqueryCondition.class, root);
        WhereClause.InSubqueryCondition inq = (WhereClause.InSubqueryCondition) root;
        assertFalse(inq.isNegated());
        assertEquals("orders", inq.getSub().tableName);
    }

    @Test
    @DisplayName("NOT IN 子查询解析")
    void notInSubquery() {
        SelectStatement stmt = (SelectStatement) parse(
                "SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM orders)");
        WhereClause.InSubqueryCondition inq = (WhereClause.InSubqueryCondition) stmt.whereClause.getRoot();
        assertTrue(inq.isNegated());
    }

    @Test
    @DisplayName("标量子查询出现在比较右侧")
    void scalarSubquery() {
        SelectStatement stmt = (SelectStatement) parse(
                "SELECT * FROM users WHERE score > (SELECT AVG(score) FROM users)");
        WhereClause.OpCondition op = (WhereClause.OpCondition) stmt.whereClause.getRoot();
        assertInstanceOf(SubqueryExpression.class, op.getValue());
        assertEquals("users", ((SubqueryExpression) op.getValue()).sub.tableName);
        // 子查询直接求值应抛出（必须先被解出）
        assertThrows(IllegalStateException.class,
                () -> op.getValue().eval(WhereClause.resolverOf(List.of(), List.of())));
    }

    @Test
    @DisplayName("标量子查询参与算术")
    void scalarSubqueryArith() {
        SelectStatement stmt = (SelectStatement) parse(
                "SELECT * FROM t WHERE score > (SELECT MAX(m) FROM m) + 10");
        WhereClause.OpCondition op = (WhereClause.OpCondition) stmt.whereClause.getRoot();
        assertInstanceOf(Expression.Arith.class, op.getValue());
        Expression.Arith arith = (Expression.Arith) op.getValue();
        assertInstanceOf(SubqueryExpression.class, arith.left);
        assertTrue(hasSubqueryText(arith));
    }

    private boolean hasSubqueryText(Expression e) {
        return e.sqlText().contains("(SELECT ...)");
    }

    // === DDL 约束：复合主键 / UNIQUE / REFERENCES ===

    @Test
    @DisplayName("表级复合主键 PRIMARY KEY (a, b)")
    void compositePrimaryKey() {
        CreateTableStatement stmt = (CreateTableStatement) parse(
                "CREATE TABLE order_items (order_id INT, item_id INT, qty INT, PRIMARY KEY (order_id, item_id))");
        assertTrue(stmt.columns.get(0).isPrimaryKey());
        assertTrue(stmt.columns.get(1).isPrimaryKey());
        assertFalse(stmt.columns.get(2).isPrimaryKey());
    }

    @Test
    @DisplayName("列级 UNIQUE 约束")
    void columnUnique() {
        CreateTableStatement stmt = (CreateTableStatement) parse(
                "CREATE TABLE users (id INT PRIMARY KEY, email VARCHAR(100) UNIQUE, name VARCHAR(50))");
        assertTrue(stmt.columns.get(1).isUnique());
        assertFalse(stmt.columns.get(2).isUnique());
    }

    @Test
    @DisplayName("表级单列 UNIQUE")
    void tableLevelUnique() {
        CreateTableStatement stmt = (CreateTableStatement) parse(
                "CREATE TABLE users (id INT, email VARCHAR(100), UNIQUE (email))");
        assertTrue(stmt.columns.get(1).isUnique());
    }

    @Test
    @DisplayName("REFERENCES 外键（显式列）")
    void foreignKey() {
        CreateTableStatement stmt = (CreateTableStatement) parse(
                "CREATE TABLE orders (id INT PRIMARY KEY, user_id INT REFERENCES users(id))");
        assertTrue(stmt.columns.get(1).isForeignKey());
        assertEquals("users", stmt.columns.get(1).referencesTable);
        assertEquals("id", stmt.columns.get(1).referencesColumn);
    }

    @Test
    @DisplayName("REFERENCES 外键（省略父列 → 父表主键）")
    void foreignKeyDefaultParentPk() {
        CreateTableStatement stmt = (CreateTableStatement) parse(
                "CREATE TABLE orders (user_id INT REFERENCES users)");
        assertTrue(stmt.columns.get(0).isForeignKey());
        assertEquals("users", stmt.columns.get(0).referencesTable);
        assertNull(stmt.columns.get(0).referencesColumn);
    }

    @Test
    @DisplayName("未定义列上的表级主键报错")
    void unknownPkColumn() {
        assertThrows(SqlParser.ParseException.class,
                () -> parse("CREATE TABLE t (a INT, PRIMARY KEY (b))"));
    }
}
