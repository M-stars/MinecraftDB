package com.minecraftdb.sql;

import com.minecraftdb.sql.SqlLexer.Token;
import com.minecraftdb.sql.SqlLexer.TokenType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlLexerTest {

    @Test
    @DisplayName("SELECT 语句词法分析")
    void tokenizeSelect() {
        SqlLexer lexer = new SqlLexer("SELECT * FROM users");
        List<Token> tokens = lexer.tokenize();

        assertEquals(TokenType.SELECT, tokens.get(0).type());
        assertEquals(TokenType.STAR, tokens.get(1).type());
        assertEquals(TokenType.FROM, tokens.get(2).type());
        assertEquals("users", tokens.get(3).value());
        assertEquals(TokenType.EOF, tokens.get(tokens.size() - 1).type());
    }

    @Test
    @DisplayName("INSERT 语句词法分析")
    void tokenizeInsert() {
        SqlLexer lexer = new SqlLexer("INSERT INTO users (id, name) VALUES (1, 'Alice')");
        List<Token> tokens = lexer.tokenize();

        assertEquals(TokenType.INSERT, tokens.get(0).type());
        assertEquals(TokenType.INTO, tokens.get(1).type());
        assertEquals("users", tokens.get(2).value());
        assertEquals(TokenType.LPAREN, tokens.get(3).type());
        assertEquals("id", tokens.get(4).value());
        assertEquals(TokenType.COMMA, tokens.get(5).type());
        assertEquals("name", tokens.get(6).value());
        assertEquals(TokenType.RPAREN, tokens.get(7).type());
        assertEquals(TokenType.VALUES, tokens.get(8).type());
        assertEquals(TokenType.LPAREN, tokens.get(9).type());
        assertEquals("1", tokens.get(10).value());  // NUMBER
        assertEquals(TokenType.COMMA, tokens.get(11).type());
        assertEquals(TokenType.STRING, tokens.get(12).type());
        assertEquals("Alice", tokens.get(12).value());
    }

    @Test
    @DisplayName("CREATE TABLE 语句词法分析")
    void tokenizeCreateTable() {
        SqlLexer lexer = new SqlLexer("CREATE TABLE users (id INT, name VARCHAR(50))");
        List<Token> tokens = lexer.tokenize();

        assertEquals(TokenType.CREATE, tokens.get(0).type());
        assertEquals(TokenType.TABLE, tokens.get(1).type());
        assertEquals("users", tokens.get(2).value());
        assertEquals(TokenType.LPAREN, tokens.get(3).type());
        assertEquals("id", tokens.get(4).value());
        assertEquals(TokenType.INT, tokens.get(5).type());
        assertEquals(TokenType.COMMA, tokens.get(6).type());
        assertEquals("name", tokens.get(7).value());
        assertEquals(TokenType.VARCHAR, tokens.get(8).type());
    }

    @Test
    @DisplayName("WHERE 条件词法分析")
    void tokenizeWhere() {
        SqlLexer lexer = new SqlLexer("SELECT * FROM users WHERE age > 18 AND name = 'Bob'");
        List<Token> tokens = lexer.tokenize();

        // 找到 WHERE 关键字
        boolean foundWhere = false;
        boolean foundAnd = false;
        for (Token t : tokens) {
            if (t.type() == TokenType.WHERE) foundWhere = true;
            if (t.type() == TokenType.AND) foundAnd = true;
        }
        assertTrue(foundWhere);
        assertTrue(foundAnd);
    }

    @Test
    @DisplayName("比较运算符词法分析")
    void tokenizeComparisons() {
        SqlLexer lexer = new SqlLexer("= != <> > < >= <=");
        List<Token> tokens = lexer.tokenize();

        assertEquals(TokenType.EQUALS, tokens.get(0).type());
        assertEquals(TokenType.NOT_EQUALS, tokens.get(1).type());
        assertEquals(TokenType.NOT_EQUALS, tokens.get(2).type());
        assertEquals(TokenType.GREATER, tokens.get(3).type());
        assertEquals(TokenType.LESS, tokens.get(4).type());
        assertEquals(TokenType.GREATER_EQ, tokens.get(5).type());
        assertEquals(TokenType.LESS_EQ, tokens.get(6).type());
    }

    @Test
    @DisplayName("事务控制语句词法分析")
    void tokenizeTransaction() {
        assertTokenType("BEGIN", TokenType.BEGIN);
        assertTokenType("COMMIT", TokenType.COMMIT);
        assertTokenType("ROLLBACK", TokenType.ROLLBACK);
    }

    @Test
    @DisplayName("SHOW/DESC 语句词法分析")
    void tokenizeShow() {
        assertTokenType("SHOW TABLES", TokenType.SHOW);
        assertTokenType("DESC users", TokenType.DESC);
        assertTokenType("SHOW INDEXES FROM users", TokenType.SHOW);
    }

    @Test
    @DisplayName("大小写不敏感")
    void caseInsensitive() {
        SqlLexer lexer = new SqlLexer("select * from users");
        List<Token> tokens = lexer.tokenize();
        assertEquals(TokenType.SELECT, tokens.get(0).type());
        assertEquals(TokenType.FROM, tokens.get(2).type());
    }

    @Test
    @DisplayName("转义字符串")
    void escapedString() {
        SqlLexer lexer = new SqlLexer("'it''s a test'");
        List<Token> tokens = lexer.tokenize();
        assertEquals(TokenType.STRING, tokens.get(0).type());
    }

    @Test
    @DisplayName("空SQL返回EOF")
    void emptySql() {
        SqlLexer lexer = new SqlLexer("");
        List<Token> tokens = lexer.tokenize();
        assertEquals(TokenType.EOF, tokens.get(0).type());
    }

    @Test
    @DisplayName("NULL关键字")
    void nullKeyword() {
        SqlLexer lexer = new SqlLexer("INSERT INTO t VALUES (NULL)");
        List<Token> tokens = lexer.tokenize();
        boolean foundNull = tokens.stream().anyMatch(t -> t.type() == TokenType.NULL_KW);
        assertTrue(foundNull);
    }

    // 辅助方法
    private void assertTokenType(String sql, TokenType expectedFirst) {
        SqlLexer lexer = new SqlLexer(sql);
        List<Token> tokens = lexer.tokenize();
        assertEquals(expectedFirst, tokens.get(0).type(), "SQL: " + sql);
    }
}
