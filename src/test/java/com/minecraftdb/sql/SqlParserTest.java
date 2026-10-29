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
        // 解析后可求值：行为断言
        List<String> cols = List.of("age");
        assertTrue(stmt.whereClause.evaluate(cols, List.of(25)));
        assertFalse(stmt.whereClause.evaluate(cols, List.of(18)));
    }

    @Test
    @DisplayName("解析 SELECT with AND")
    void parseSelectWhereAnd() {
        SelectStatement stmt = (SelectStatement) parse(
            "SELECT * FROM users WHERE age > 18 AND name = 'Bob'");
        assertNotNull(stmt.whereClause);
        List<String> cols = List.of("age", "name");
        assertTrue(stmt.whereClause.evaluate(cols, List.of(25, "Bob")));
        assertFalse(stmt.whereClause.evaluate(cols, List.of(25, "Alice")));
        assertFalse(stmt.whereClause.evaluate(cols, List.of(17, "Bob")));
    }

    @Test
    @DisplayName("解析 INSERT INTO")
    void parseInsert() {
        InsertStatement stmt = (InsertStatement) parse(
            "INSERT INTO users (id, name, age) VALUES (1, 'Alice', 25)");
        assertEquals("users", stmt.tableName);
        assertEquals(List.of("id", "name", "age"), stmt.columns);
        assertEquals(1, stmt.valueRows.size());
        assertEquals(3, stmt.valueRows.get(0).size());
        assertEquals("1", stmt.valueRows.get(0).get(0));
        assertEquals("'Alice'", stmt.valueRows.get(0).get(1));
        assertEquals("25", stmt.valueRows.get(0).get(2));
    }

    @Test
    @DisplayName("解析 INSERT 多组 VALUES")
    void parseInsertMultiValues() {
        InsertStatement stmt = (InsertStatement) parse(
            "INSERT INTO users (id, name) VALUES (1, 'Alice'), (2, 'Bob'), (3, 'Carol')");
        assertEquals(3, stmt.valueRows.size());
        assertEquals("1", stmt.valueRows.get(0).get(0));
        assertEquals("'Bob'", stmt.valueRows.get(1).get(1));
        assertEquals("'Carol'", stmt.valueRows.get(2).get(1));
    }

    @Test
    @DisplayName("解析 SELECT with ORDER BY / LIMIT / OFFSET")
    void parseSelectPaging() {
        SelectStatement stmt = (SelectStatement) parse(
            "SELECT * FROM users WHERE age > 18 ORDER BY age DESC, name ASC LIMIT 10 OFFSET 5");
        assertNotNull(stmt.whereClause);
        assertEquals(2, stmt.orderBy.size());
        assertEquals("age", stmt.orderBy.get(0).column());
        assertFalse(stmt.orderBy.get(0).ascending());
        assertEquals("name", stmt.orderBy.get(1).column());
        assertTrue(stmt.orderBy.get(1).ascending());
        assertEquals(10, stmt.limit);
        assertEquals(5, stmt.offset);
    }

    @Test
    @DisplayName("解析 SELECT with LIMIT（MySQL 风格 offset,count）")
    void parseSelectLimitComma() {
        SelectStatement stmt = (SelectStatement) parse("SELECT * FROM users LIMIT 5, 10");
        assertEquals(10, stmt.limit);
        assertEquals(5, stmt.offset);
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
    @DisplayName("解析 DEFAULT 与 AUTO_INCREMENT")
    void parseColumnDefaults() {
        CreateTableStatement stmt = (CreateTableStatement) parse(
            "CREATE TABLE users (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50) DEFAULT 'anon', score INT DEFAULT 0)");
        assertTrue(stmt.columns.get(0).autoIncrement);
        assertEquals("'anon'", stmt.columns.get(1).defaultValue);
        assertEquals("0", stmt.columns.get(2).defaultValue);
    }

    @Test
    @DisplayName("解析 IF NOT EXISTS / IF EXISTS")
    void parseIdempotentDdl() {
        CreateTableStatement create = (CreateTableStatement) parse(
            "CREATE TABLE IF NOT EXISTS users (id INT)");
        assertTrue(create.ifNotExists);

        CreateTableStatement create2 = (CreateTableStatement) parse(
            "CREATE TABLE users (id INT)");
        assertFalse(create2.ifNotExists);

        DropTableStatement drop = (DropTableStatement) parse("DROP TABLE IF EXISTS users");
        assertTrue(drop.ifExists);

        DropTableStatement drop2 = (DropTableStatement) parse("DROP TABLE users");
        assertFalse(drop2.ifExists);
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

    @Test
    @DisplayName("解析聚合函数与 GROUP BY")
    void parseAggregateAndGroupBy() {
        SelectStatement stmt = (SelectStatement) parse(
            "SELECT category, COUNT(*), AVG(price) FROM products WHERE price > 0 GROUP BY category ORDER BY COUNT(*) DESC LIMIT 5");
        assertNotNull(stmt.selectItems);
        assertEquals(3, stmt.selectItems.size());
        assertNull(stmt.selectItems.get(0).function());
        assertEquals("category", stmt.selectItems.get(0).column());
        assertEquals("COUNT", stmt.selectItems.get(1).function());
        assertEquals("*", stmt.selectItems.get(1).column());
        assertEquals("AVG", stmt.selectItems.get(2).function());
        assertEquals("price", stmt.selectItems.get(2).column());
        assertEquals(List.of("category"), stmt.groupBy);
        assertEquals(1, stmt.orderBy.size());
        assertEquals(5, stmt.limit);
    }

    @Test
    @DisplayName("解析 DISTINCT 与 AS 别名")
    void parseDistinctAndAlias() {
        SelectStatement stmt = (SelectStatement) parse(
            "SELECT DISTINCT category AS cat FROM products");
        assertTrue(stmt.distinct);
        assertEquals("cat", stmt.selectItems.get(0).label());
        assertEquals("category", stmt.selectItems.get(0).column());
    }

    @Test
    @DisplayName("解析 SHOW CREATE TABLE")
    void parseShowCreateTable() {
        ShowCreateTableStatement stmt = (ShowCreateTableStatement) parse("SHOW CREATE TABLE users");
        assertEquals("users", stmt.tableName);
    }
}
