package com.minecraftdb.sql;

import com.minecraftdb.sql.SqlLexer.Token;
import com.minecraftdb.sql.ast.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlParserTest {

    private Statement parse(String sql) {
        SqlLexer lexer = new SqlLexer(sql);
        List<Token> tokens = lexer.tokenize();
        SqlParser parser = new SqlParser(tokens);
        return parser.parse();
    }

    @Test
    @DisplayName("解析 SELECT * FROM table")
    void parseSelectAll() {
        SelectStatement stmt = (SelectStatement) parse("SELECT * FROM users");
        assertEquals("users", stmt.tableName);
        assertTrue(stmt.isSelectAll());
        assertNull(stmt.whereClause);
    }

    @Test
    @DisplayName("解析 SELECT 指定列")
    void parseSelectColumns() {
        SelectStatement stmt = (SelectStatement) parse("SELECT id, name, age FROM users");
        assertEquals("users", stmt.tableName);
        assertEquals(List.of("id", "name", "age"), stmt.columns);
        assertFalse(stmt.isSelectAll());
    }

    @Test
    @DisplayName("解析 SELECT with WHERE")
    void parseSelectWhere() {
        SelectStatement stmt = (SelectStatement) parse("SELECT * FROM users WHERE age > 18");
        assertEquals("users", stmt.tableName);
        assertNotNull(stmt.whereClause);
        assertEquals(1, stmt.whereClause.conditions.size());
        assertEquals("age", stmt.whereClause.conditions.get(0).column);
        assertEquals(">", stmt.whereClause.conditions.get(0).operator);
        assertEquals("18", stmt.whereClause.conditions.get(0).value);
    }

    @Test
    @DisplayName("解析 SELECT with AND")
    void parseSelectWhereAnd() {
        SelectStatement stmt = (SelectStatement) parse(
            "SELECT * FROM users WHERE age > 18 AND name = 'Bob'");
        assertNotNull(stmt.whereClause);
        assertEquals(2, stmt.whereClause.conditions.size());
        assertEquals(1, stmt.whereClause.logicalOps.size());
        assertEquals(WhereClause.LogicalOp.AND, stmt.whereClause.logicalOps.get(0));
    }

    @Test
    @DisplayName("解析 INSERT INTO")
    void parseInsert() {
        InsertStatement stmt = (InsertStatement) parse(
            "INSERT INTO users (id, name, age) VALUES (1, 'Alice', 25)");
        assertEquals("users", stmt.tableName);
        assertEquals(List.of("id", "name", "age"), stmt.columns);
        assertEquals(3, stmt.values.size());
        assertEquals("1", stmt.values.get(0));
        assertEquals("'Alice'", stmt.values.get(1));
        assertEquals("25", stmt.values.get(2));
    }

    @Test
    @DisplayName("解析 UPDATE with SET")
    void parseUpdate() {
        UpdateStatement stmt = (UpdateStatement) parse(
            "UPDATE users SET age = 30 WHERE name = 'Bob'");
        assertEquals("users", stmt.tableName);
        assertTrue(stmt.assignments.containsKey("age"));
        assertEquals("30", stmt.assignments.get("age"));
        assertNotNull(stmt.whereClause);
    }

    @Test
    @DisplayName("解析 DELETE FROM")
    void parseDelete() {
        DeleteStatement stmt = (DeleteStatement) parse("DELETE FROM users WHERE id = 1");
        assertEquals("users", stmt.tableName);
        assertNotNull(stmt.whereClause);
    }

    @Test
    @DisplayName("解析 DELETE 无条件（全表删除）")
    void parseDeleteAll() {
        DeleteStatement stmt = (DeleteStatement) parse("DELETE FROM users");
        assertEquals("users", stmt.tableName);
        assertNull(stmt.whereClause);
    }

    @Test
    @DisplayName("解析 CREATE TABLE")
    void parseCreateTable() {
        CreateTableStatement stmt = (CreateTableStatement) parse(
            "CREATE TABLE users (id INT, name VARCHAR(50), age INT)");
        assertEquals("users", stmt.tableName);
        assertEquals(3, stmt.columns.size());
        assertEquals("id", stmt.columns.get(0).name);
        assertEquals("INT", stmt.columns.get(0).dataType);
        assertEquals("name", stmt.columns.get(1).name);
        assertEquals("VARCHAR(50)", stmt.columns.get(1).dataType);
    }

    @Test
    @DisplayName("解析 CREATE TABLE with NOT NULL")
    void parseCreateTableWithConstraints() {
        CreateTableStatement stmt = (CreateTableStatement) parse(
            "CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))");
        assertEquals(2, stmt.columns.size());
        assertFalse(stmt.columns.get(0).isNullable());
        assertTrue(stmt.columns.get(1).isNullable());
    }

    @Test
    @DisplayName("解析 CREATE TABLE with PRIMARY KEY")
    void parseCreateTableWithPk() {
        CreateTableStatement stmt = (CreateTableStatement) parse(
            "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))");
        assertTrue(stmt.columns.get(0).isPrimaryKey());
    }

    @Test
    @DisplayName("解析 DROP TABLE")
    void parseDropTable() {
        DropTableStatement stmt = (DropTableStatement) parse("DROP TABLE users");
        assertEquals("users", stmt.tableName);
    }

    @Test
    @DisplayName("解析 ALTER TABLE ADD")
    void parseAlterAdd() {
        AlterTableStatement stmt = (AlterTableStatement) parse(
            "ALTER TABLE users ADD email VARCHAR(100)");
        assertEquals("users", stmt.tableName);
        assertEquals("ADD", stmt.action);
        assertEquals("email", stmt.column.name);
        assertEquals("VARCHAR(100)", stmt.column.dataType);
    }

    @Test
    @DisplayName("解析 ALTER TABLE DROP")
    void parseAlterDrop() {
        AlterTableStatement stmt = (AlterTableStatement) parse(
            "ALTER TABLE users DROP age");
        assertEquals("users", stmt.tableName);
        assertEquals("DROP", stmt.action);
        assertEquals("age", stmt.dropColumnName);
    }

    @Test
    @DisplayName("解析 SHOW TABLES")
    void parseShowTables() {
        Statement stmt = parse("SHOW TABLES");
        assertInstanceOf(ShowTablesStatement.class, stmt);
    }

    @Test
    @DisplayName("解析 DESC table")
    void parseDescTable() {
        DescTableStatement stmt = (DescTableStatement) parse("DESC users");
        assertEquals("users", stmt.tableName);
    }

    @Test
    @DisplayName("解析 BEGIN / COMMIT / ROLLBACK")
    void parseTransactions() {
        TransactionStatement begin = (TransactionStatement) parse("BEGIN");
        assertEquals(TransactionStatement.TxnType.BEGIN, begin.txnType);

        TransactionStatement commit = (TransactionStatement) parse("COMMIT");
        assertEquals(TransactionStatement.TxnType.COMMIT, commit.txnType);

        TransactionStatement rollback = (TransactionStatement) parse("ROLLBACK");
        assertEquals(TransactionStatement.TxnType.ROLLBACK, rollback.txnType);
    }

    @Test
    @DisplayName("解析 CREATE INDEX")
    void parseCreateIndex() {
        CreateIndexStatement stmt = (CreateIndexStatement) parse(
            "CREATE INDEX idx_name ON users (name)");
        assertEquals("idx_name", stmt.indexName);
        assertEquals("users", stmt.tableName);
        assertEquals("name", stmt.columnName);
    }

    @Test
    @DisplayName("解析失败：垃圾SQL")
    void parseGarbage() {
        assertThrows(SqlParser.ParseException.class, () -> parse("GARBAGE SQL HERE"));
    }

    @Test
    @DisplayName("解析失败：不完整的SQL")
    void parseIncomplete() {
        assertThrows(SqlParser.ParseException.class, () -> parse("SELECT * FROM"));
    }
}
