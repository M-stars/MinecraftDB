package com.minecraftdb.sql;

import com.minecraftdb.sql.SqlLexer.Token;
import com.minecraftdb.sql.ast.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：完整的 词法分析 → 语法分析 → AST 流程
 */
class SqlIntegrationTest {

    @Test
    @DisplayName("完整CRUD流程解析测试")
    void fullCrudParsing() {
        // CREATE TABLE
        CreateTableStatement create = parse("CREATE TABLE users (id INT, name VARCHAR(50), age INT)");
        assertEquals("users", create.tableName);
        assertEquals(3, create.columns.size());

        // INSERT
        InsertStatement insert = parse("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 25)");
        assertEquals("users", insert.tableName);
        assertEquals("'Alice'", insert.valueRows.get(0).get(1));

        // SELECT
        SelectStatement select = parse("SELECT * FROM users WHERE age > 18");
        assertEquals("users", select.tableName);
        assertNotNull(select.whereClause);

        // UPDATE
        UpdateStatement update = parse("UPDATE users SET age = 26 WHERE id = 1");
        assertEquals("users", update.tableName);
        assertEquals("26", update.assignments.get("age"));

        // DELETE
        DeleteStatement delete = parse("DELETE FROM users WHERE id = 1");
        assertEquals("users", delete.tableName);
        assertNotNull(delete.whereClause);
    }

    @Test
    @DisplayName("多种数据类型解析")
    void variousDataTypes() {
        CreateTableStatement stmt = parse(
            "CREATE TABLE all_types (" +
            "id INT, amount BIGINT, name VARCHAR(100), description TEXT, " +
            "active BOOLEAN, score FLOAT, rating DOUBLE, created DATE)");
        assertEquals(8, stmt.columns.size());
        assertEquals("INT", stmt.columns.get(0).dataType);
        assertEquals("BIGINT", stmt.columns.get(1).dataType);
        assertEquals("VARCHAR(100)", stmt.columns.get(2).dataType);
        assertEquals("TEXT", stmt.columns.get(3).dataType);
        assertEquals("BOOLEAN", stmt.columns.get(4).dataType);
        assertEquals("FLOAT", stmt.columns.get(5).dataType);
        assertEquals("DOUBLE", stmt.columns.get(6).dataType);
        assertEquals("DATE", stmt.columns.get(7).dataType);
    }

    @Test
    @DisplayName("复杂 WHERE 子句解析")
    void complexWhere() {
        SelectStatement stmt = parse(
            "SELECT * FROM products WHERE price > 100 AND category = 'electronics' OR stock < 5");
        assertEquals("products", stmt.tableName);
        assertEquals(3, stmt.whereClause.conditions.size());
        assertEquals(2, stmt.whereClause.logicalOps.size());
    }

    @Test
    @DisplayName("UPDATE 多字段更新")
    void updateMultipleFields() {
        UpdateStatement stmt = parse("UPDATE users SET name = 'Bob', age = 30 WHERE id = 2");
        assertEquals(2, stmt.assignments.size());
        assertEquals("'Bob'", stmt.assignments.get("name"));
        assertEquals("30", stmt.assignments.get("age"));
    }

    @Test
    @DisplayName("带分号的SQL解析")
    void sqlWithSemicolon() {
        // 分号是可选的，末尾分号应该被忽略
        Statement stmt = parse("SHOW TABLES;");
        assertInstanceOf(ShowTablesStatement.class, stmt);

        stmt = parse("BEGIN;");
        assertInstanceOf(TransactionStatement.class, stmt);
    }

    @Test
    @DisplayName("NULL 值插入解析")
    void insertNull() {
        InsertStatement stmt = parse("INSERT INTO users (id, name) VALUES (1, null)");
        assertEquals("null", stmt.valueRows.get(0).get(1));
    }

    @Test
    @DisplayName("负数比较解析")
    void negativeNumberWhere() {
        SelectStatement stmt = parse("SELECT * FROM accounts WHERE balance < -100");
        assertEquals("balance", stmt.whereClause.conditions.get(0).column);
        assertEquals("<", stmt.whereClause.conditions.get(0).operator);
        assertEquals("-100", stmt.whereClause.conditions.get(0).value);
    }

    @Test
    @DisplayName("所有比较运算符")
    void allComparisonOperators() {
        String[] ops = {"=", "!=", "<>", ">", "<", ">=", "<="};
        for (String op : ops) {
            String sql = "SELECT * FROM t WHERE x " + op + " 5";
            SelectStatement stmt = parse(sql);
            assertEquals(op, stmt.whereClause.conditions.get(0).operator,
                    "运算符解析失败: " + op);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Statement> T parse(String sql) {
        SqlLexer lexer = new SqlLexer(sql);
        List<Token> tokens = lexer.tokenize();
        SqlParser parser = new SqlParser(tokens);
        return (T) parser.parse();
    }
}
