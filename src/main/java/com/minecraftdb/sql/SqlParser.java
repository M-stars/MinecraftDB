package com.minecraftdb.sql;

import com.minecraftdb.sql.SqlLexer.Token;
import com.minecraftdb.sql.SqlLexer.TokenType;
import com.minecraftdb.sql.ast.*;
import com.minecraftdb.sql.ast.WhereClause.LogicalOp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SqlParser {

    private final List<Token> tokens;
    private int pos;

    public SqlParser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    public Statement parse() {
        Token first = peek();
        if (first.type() == TokenType.EOF) {
            throw new ParseException("Empty SQL statement");
        }

        return switch (first.type()) {
            case SELECT -> parseSelect();
            case INSERT -> parseInsert();
            case UPDATE -> parseUpdate();
            case DELETE -> parseDelete();
            case CREATE -> parseCreate();
            case DROP -> parseDrop();
            case ALTER -> parseAlter();
            case SHOW -> parseShow();
            case DESC -> parseDesc();
            case BEGIN -> parseBegin();
            case COMMIT -> parseCommit();
            case ROLLBACK -> parseRollback();
            default -> throw new ParseException("Unexpected token: " + first.value());
        };
    }

    // === SELECT ===

    private SelectStatement parseSelect() {
        expect(TokenType.SELECT);

        List<String> columns = new ArrayList<>();
        if (peek().type() == TokenType.STAR) {
            columns.add("*");
            advance();
        } else {
            columns.add(parseIdentifier());
            while (peek().type() == TokenType.COMMA) {
                advance();
                columns.add(parseIdentifier());
            }
        }

        expect(TokenType.FROM);
        String tableName = parseIdentifier();

        WhereClause whereClause = null;
        if (peek().type() == TokenType.WHERE) {
            whereClause = parseWhereClause();
        }

        expectOptional(TokenType.SEMICOLON);
        return new SelectStatement(columns, tableName, whereClause);
    }

    // === INSERT ===

    private InsertStatement parseInsert() {
        expect(TokenType.INSERT);
        expect(TokenType.INTO);
        String tableName = parseIdentifier();

        List<String> columns = new ArrayList<>();
        if (peek().type() == TokenType.LPAREN) {
            advance();
            columns.add(parseIdentifier());
            while (peek().type() == TokenType.COMMA) {
                advance();
                columns.add(parseIdentifier());
            }
            expect(TokenType.RPAREN);
        }

        expect(TokenType.VALUES);
        expect(TokenType.LPAREN);
        List<String> values = new ArrayList<>();
        values.add(parseValue());
        while (peek().type() == TokenType.COMMA) {
            advance();
            values.add(parseValue());
        }
        expect(TokenType.RPAREN);

        expectOptional(TokenType.SEMICOLON);
        return new InsertStatement(tableName, columns, values);
    }

    // === UPDATE ===

    private UpdateStatement parseUpdate() {
        expect(TokenType.UPDATE);
        String tableName = parseIdentifier();
        expect(TokenType.SET);

        Map<String, String> assignments = new LinkedHashMap<>();
        String col = parseIdentifier();
        expect(TokenType.EQUALS);
        {
            String val = parseValue();
            assignments.put(col, val);
        }
        while (peek().type() == TokenType.COMMA) {
            advance();
            String c = parseIdentifier();
            expect(TokenType.EQUALS);
            String v = parseValue();
            assignments.put(c, v);
        }

        WhereClause whereClause = null;
        if (peek().type() == TokenType.WHERE) {
            whereClause = parseWhereClause();
        }

        expectOptional(TokenType.SEMICOLON);
        return new UpdateStatement(tableName, assignments, whereClause);
    }

    // === DELETE ===

    private DeleteStatement parseDelete() {
        expect(TokenType.DELETE);
        expect(TokenType.FROM);
        String tableName = parseIdentifier();

        WhereClause whereClause = null;
        if (peek().type() == TokenType.WHERE) {
            whereClause = parseWhereClause();
        }

        expectOptional(TokenType.SEMICOLON);
        return new DeleteStatement(tableName, whereClause);
    }

    // === CREATE ===

    private Statement parseCreate() {
        expect(TokenType.CREATE);
        if (peek().type() == TokenType.TABLE) {
            return parseCreateTable();
        } else if (peek().type() == TokenType.INDEX) {
            return parseCreateIndex();
        }
        throw new ParseException("Expected TABLE or INDEX after CREATE, got: " + peek().value());
    }

    private CreateTableStatement parseCreateTable() {
        expect(TokenType.TABLE);
        String tableName = parseIdentifier();
        expect(TokenType.LPAREN);

        List<ColumnDefinition> columns = new ArrayList<>();
        columns.add(parseColumnDef());
        while (peek().type() == TokenType.COMMA) {
            advance();
            // Check if next is a table constraint (PRIMARY KEY)
            if (peek().type() == TokenType.PRIMARY) {
                advance();
                expect(TokenType.KEY);
                expect(TokenType.LPAREN);
                String pkCol = parseIdentifier();
                expect(TokenType.RPAREN);
                for (ColumnDefinition cd : columns) {
                    if (cd.name.equalsIgnoreCase(pkCol)) {
                        cd.addConstraint("PRIMARY KEY");
                    }
                }
            } else {
                columns.add(parseColumnDef());
            }
        }
        expect(TokenType.RPAREN);
        expectOptional(TokenType.SEMICOLON);

        return new CreateTableStatement(tableName, columns);
    }

    private CreateIndexStatement parseCreateIndex() {
        expect(TokenType.INDEX);
        String indexName = parseIdentifier();
        expect(TokenType.ON);
        String tableName = parseIdentifier();
        expect(TokenType.LPAREN);
        String columnName = parseIdentifier();
        expect(TokenType.RPAREN);
        expectOptional(TokenType.SEMICOLON);

        return new CreateIndexStatement(indexName, tableName, columnName);
    }

    // === DROP ===

    private Statement parseDrop() {
        expect(TokenType.DROP);
        expect(TokenType.TABLE);
        String tableName = parseIdentifier();
        expectOptional(TokenType.SEMICOLON);
        return new DropTableStatement(tableName);
    }

    // === ALTER ===

    private AlterTableStatement parseAlter() {
        expect(TokenType.ALTER);
        expect(TokenType.TABLE);
        String tableName = parseIdentifier();

        if (peek().type() == TokenType.ADD) {
            advance();
            ColumnDefinition col = parseColumnDef();
            expectOptional(TokenType.SEMICOLON);
            return new AlterTableStatement(tableName, "ADD", col, null);
        } else if (peek().type() == TokenType.DROP) {
            advance();
            String dropCol = parseIdentifier();
            expectOptional(TokenType.SEMICOLON);
            return new AlterTableStatement(tableName, "DROP", null, dropCol);
        }
        throw new ParseException("Expected ADD or DROP after table name in ALTER TABLE");
    }

    // === SHOW ===

    private Statement parseShow() {
        expect(TokenType.SHOW);
        if (peek().type() == TokenType.TABLES) {
            advance();
            expectOptional(TokenType.SEMICOLON);
            return new ShowTablesStatement();
        } else if (peek().type() == TokenType.INDEXES) {
            advance();
            expect(TokenType.FROM);
            String tableName = parseIdentifier();
            expectOptional(TokenType.SEMICOLON);
            return new ShowIndexesStatement(tableName);
        }
        throw new ParseException("Expected TABLES or INDEXES after SHOW, got: " + peek().value());
    }

    // === DESC ===

    private DescTableStatement parseDesc() {
        expect(TokenType.DESC);
        String tableName = parseIdentifier();
        expectOptional(TokenType.SEMICOLON);
        return new DescTableStatement(tableName);
    }

    // === Transaction statements ===

    private TransactionStatement parseBegin() {
        expect(TokenType.BEGIN);
        expectOptional(TokenType.SEMICOLON);
        return new TransactionStatement(TransactionStatement.TxnType.BEGIN);
    }

    private TransactionStatement parseCommit() {
        expect(TokenType.COMMIT);
        expectOptional(TokenType.SEMICOLON);
        return new TransactionStatement(TransactionStatement.TxnType.COMMIT);
    }

    private TransactionStatement parseRollback() {
        expect(TokenType.ROLLBACK);
        expectOptional(TokenType.SEMICOLON);
        return new TransactionStatement(TransactionStatement.TxnType.ROLLBACK);
    }

    // === WHERE clause ===

    private WhereClause parseWhereClause() {
        expect(TokenType.WHERE);
        WhereClause wc = new WhereClause();

        wc.addCondition(parseCondition());
        while (peek().type() == TokenType.AND || peek().type() == TokenType.OR) {
            LogicalOp op = peek().type() == TokenType.AND ? LogicalOp.AND : LogicalOp.OR;
            advance();
            wc.addLogicalOp(op);
            wc.addCondition(parseCondition());
        }

        return wc;
    }

    private WhereClause.Condition parseCondition() {
        String column = parseIdentifier();
        String operator = parseComparisonOp();
        String value = parseValue();
        return new WhereClause.Condition(column, operator, value);
    }

    // === Helpers ===

    private ColumnDefinition parseColumnDef() {
        String name = parseIdentifier();
        String dataType = parseDataType();
        ColumnDefinition cd = new ColumnDefinition(name, dataType);

        // Parse constraints
        while (true) {
            if (peek().type() == TokenType.NOT) {
                advance();
                expect(TokenType.NULL_KW);
                cd.addConstraint("NOT NULL");
            } else if (peek().type() == TokenType.PRIMARY) {
                advance();
                expect(TokenType.KEY);
                cd.addConstraint("PRIMARY KEY");
            } else if (peek().type() == TokenType.NULL_KW) {
                advance();
                cd.addConstraint("NULL");
            } else {
                break;
            }
        }
        return cd;
    }

    private String parseDataType() {
        Token t = peek();
        if (t.type() == TokenType.VARCHAR) {
            advance();
            if (peek().type() == TokenType.LPAREN) {
                advance();
                String size = peek().value();
                advance(); // number
                expect(TokenType.RPAREN);
                return "VARCHAR(" + size + ")";
            }
            return "VARCHAR";
        }
        if (t.type() == TokenType.INT || t.type() == TokenType.BIGINT || t.type() == TokenType.TEXT
                || t.type() == TokenType.BOOLEAN || t.type() == TokenType.FLOAT || t.type() == TokenType.DOUBLE
                || t.type() == TokenType.DATE || t.type() == TokenType.DATETIME) {
            advance();
            return t.value().toUpperCase();
        }
        throw new ParseException("Expected data type, got: " + t.value());
    }

    private String parseIdentifier() {
        Token t = peek();
        if (t.type() == TokenType.IDENTIFIER || isDataTypeToken(t.type())) {
            advance();
            return t.value();
        }
        // Allow some keywords to be used as identifiers
        if (t.type() == TokenType.INDEX || t.type() == TokenType.INDEXES || t.type() == TokenType.TABLES) {
            advance();
            return t.value();
        }
        throw new ParseException("Expected identifier, got: " + t.value() + " (" + t.type() + ")");
    }

    private boolean isDataTypeToken(TokenType type) {
        return type == TokenType.VARCHAR || type == TokenType.INT || type == TokenType.BIGINT
                || type == TokenType.TEXT || type == TokenType.BOOLEAN || type == TokenType.FLOAT
                || type == TokenType.DOUBLE || type == TokenType.DATE || type == TokenType.DATETIME;
    }

    private String parseValue() {
        Token t = peek();
        if (t.type() == TokenType.STRING) {
            advance();
            return "'" + t.value() + "'";
        }
        if (t.type() == TokenType.NUMBER) {
            advance();
            return t.value();
        }
        if (t.type() == TokenType.NULL_KW) {
            advance();
            return "null";
        }
        if (t.type() == TokenType.IDENTIFIER) {
            advance();
            return t.value();
        }
        throw new ParseException("Expected value, got: " + t.value());
    }

    private String parseComparisonOp() {
        Token t = peek();
        return switch (t.type()) {
            case EQUALS -> { advance(); yield "="; }
            case NOT_EQUALS -> {
                advance();
                yield t.value(); // != or <>
            }
            case GREATER -> { advance(); yield ">"; }
            case LESS -> { advance(); yield "<"; }
            case GREATER_EQ -> { advance(); yield ">="; }
            case LESS_EQ -> { advance(); yield "<="; }
            default -> throw new ParseException("Expected comparison operator, got: " + t.value());
        };
    }

    // === Token stream helpers ===

    private Token peek() {
        return pos < tokens.size() ? tokens.get(pos) : new Token(TokenType.EOF, "");
    }

    private Token advance() {
        Token t = peek();
        pos++;
        return t;
    }

    private void expect(TokenType type) {
        Token t = advance();
        if (t.type() != type) {
            throw new ParseException("Expected " + type + " but got " + t.type() + " ('" + t.value() + "')");
        }
    }

    private void expectOptional(TokenType type) {
        if (peek().type() == type) {
            advance();
        }
    }

    // === Exception ===

    public static class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }
}
