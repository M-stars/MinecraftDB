package com.minecraftdb.sql;

import java.util.ArrayList;
import java.util.List;

public class SqlLexer {

    private final String input;
    private int pos;
    private final int len;

    public SqlLexer(String input) {
        this.input = input != null ? input : "";
        this.pos = 0;
        this.len = this.input.length();
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (pos < len) {
            char c = peek();

            if (Character.isWhitespace(c)) {
                advance();
                continue;
            }

            if (c == '-' && peek(1) == '-') {
                skipLineComment();
                continue;
            }

            if (c == '/' && peek(1) == '*') {
                skipBlockComment();
                continue;
            }

            if (c == '\'' || c == '"') {
                tokens.add(readString(c));
                continue;
            }

            if (Character.isDigit(c) || (c == '-' && Character.isDigit(peek(1)))) {
                tokens.add(readNumber());
                continue;
            }

            if (Character.isLetter(c) || c == '_') {
                tokens.add(readIdentifierOrKeyword());
                continue;
            }

            // Operators and punctuation
            Token opToken = readOperator();
            if (opToken != null) {
                tokens.add(opToken);
                continue;
            }

            // Skip unknown characters
            advance();
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }

    private char peek() {
        return pos < len ? input.charAt(pos) : '\0';
    }

    private char peek(int ahead) {
        int idx = pos + ahead;
        return idx < len ? input.charAt(idx) : '\0';
    }

    private void advance() {
        pos++;
    }

    private void skipLineComment() {
        while (pos < len && input.charAt(pos) != '\n') {
            advance();
        }
    }

    private void skipBlockComment() {
        advance(); advance(); // skip /*
        while (pos < len) {
            if (peek() == '*' && peek(1) == '/') {
                advance(); advance();
                return;
            }
            advance();
        }
    }

    private Token readString(char quote) {
        advance(); // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < len) {
            char c = peek();
            if (c == '\\' && peek(1) == quote) {
                sb.append(quote);
                advance(); advance();
                continue;
            }
            if (c == quote) {
                advance();
                break;
            }
            sb.append(c);
            advance();
        }
        return new Token(TokenType.STRING, sb.toString());
    }

    private Token readNumber() {
        StringBuilder sb = new StringBuilder();
        if (peek() == '-') {
            sb.append(peek());
            advance();
        }
        while (pos < len && (Character.isDigit(peek()) || peek() == '.')) {
            sb.append(peek());
            advance();
        }
        return new Token(TokenType.NUMBER, sb.toString());
    }

    private Token readIdentifierOrKeyword() {
        StringBuilder sb = new StringBuilder();
        while (pos < len && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
            sb.append(peek());
            advance();
        }
        String word = sb.toString();
        TokenType keywordType = Keywords.match(word);
        if (keywordType != null) {
            return new Token(keywordType, word);
        }
        return new Token(TokenType.IDENTIFIER, word);
    }

    private Token readOperator() {
        char c = peek();
        return switch (c) {
            case '(' -> { advance(); yield new Token(TokenType.LPAREN, "("); }
            case ')' -> { advance(); yield new Token(TokenType.RPAREN, ")"); }
            case ',' -> { advance(); yield new Token(TokenType.COMMA, ","); }
            case ';' -> { advance(); yield new Token(TokenType.SEMICOLON, ";"); }
            case '*' -> { advance(); yield new Token(TokenType.STAR, "*"); }
            case '=' -> { advance(); yield new Token(TokenType.EQUALS, "="); }
            case '>' -> {
                advance();
                if (peek() == '=') { advance(); yield new Token(TokenType.GREATER_EQ, ">="); }
                yield new Token(TokenType.GREATER, ">");
            }
            case '<' -> {
                advance();
                if (peek() == '=') { advance(); yield new Token(TokenType.LESS_EQ, "<="); }
                if (peek() == '>') { advance(); yield new Token(TokenType.NOT_EQUALS, "<>"); }
                yield new Token(TokenType.LESS, "<");
            }
            case '!' -> {
                advance();
                if (peek() == '=') { advance(); yield new Token(TokenType.NOT_EQUALS, "!="); }
                yield null;
            }
            default -> null;
        };
    }

    // === Token types ===

    public enum TokenType {
        // Keywords
        SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, ADD,
        TABLE, INTO, VALUES, FROM, WHERE, SET,
        AND, OR, NOT, NULL_KW, PRIMARY, KEY,
        BEGIN, COMMIT, ROLLBACK,
        SHOW, TABLES, DESC, INDEX, INDEXES, ON,
        VARCHAR, INT, BIGINT, TEXT, BOOLEAN, FLOAT, DOUBLE, DATE, DATETIME,

        // Identifiers and literals
        IDENTIFIER, STRING, NUMBER,

        // Operators and punctuation
        EQUALS, NOT_EQUALS, GREATER, LESS, GREATER_EQ, LESS_EQ,
        LPAREN, RPAREN, COMMA, SEMICOLON, STAR,

        EOF
    }

    public record Token(TokenType type, String value) {
        @Override
        public String toString() {
            return String.format("Token[%s: '%s']", type, value);
        }
    }

    // === Keyword mapping ===

    private static class Keywords {
        private static final java.util.Map<String, TokenType> MAP = new java.util.HashMap<>();

        static {
            MAP.put("select", TokenType.SELECT);
            MAP.put("insert", TokenType.INSERT);
            MAP.put("update", TokenType.UPDATE);
            MAP.put("delete", TokenType.DELETE);
            MAP.put("create", TokenType.CREATE);
            MAP.put("drop", TokenType.DROP);
            MAP.put("alter", TokenType.ALTER);
            MAP.put("add", TokenType.ADD);
            MAP.put("table", TokenType.TABLE);
            MAP.put("into", TokenType.INTO);
            MAP.put("values", TokenType.VALUES);
            MAP.put("from", TokenType.FROM);
            MAP.put("where", TokenType.WHERE);
            MAP.put("set", TokenType.SET);
            MAP.put("and", TokenType.AND);
            MAP.put("or", TokenType.OR);
            MAP.put("not", TokenType.NOT);
            MAP.put("null", TokenType.NULL_KW);
            MAP.put("primary", TokenType.PRIMARY);
            MAP.put("key", TokenType.KEY);
            MAP.put("begin", TokenType.BEGIN);
            MAP.put("commit", TokenType.COMMIT);
            MAP.put("rollback", TokenType.ROLLBACK);
            MAP.put("show", TokenType.SHOW);
            MAP.put("tables", TokenType.TABLES);
            MAP.put("desc", TokenType.DESC);
            MAP.put("describe", TokenType.DESC);
            MAP.put("index", TokenType.INDEX);
            MAP.put("indexes", TokenType.INDEXES);
            MAP.put("on", TokenType.ON);
            MAP.put("varchar", TokenType.VARCHAR);
            MAP.put("int", TokenType.INT);
            MAP.put("integer", TokenType.INT);
            MAP.put("bigint", TokenType.BIGINT);
            MAP.put("text", TokenType.TEXT);
            MAP.put("boolean", TokenType.BOOLEAN);
            MAP.put("bool", TokenType.BOOLEAN);
            MAP.put("float", TokenType.FLOAT);
            MAP.put("double", TokenType.DOUBLE);
            MAP.put("date", TokenType.DATE);
            MAP.put("datetime", TokenType.DATETIME);
        }

        static TokenType match(String word) {
            return MAP.get(word.toLowerCase());
        }
    }
}
