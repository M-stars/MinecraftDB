package com.minecraftdb.sql;

import com.minecraftdb.sql.SqlLexer.Token;
import com.minecraftdb.sql.SqlLexer.TokenType;
import com.minecraftdb.sql.ast.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
            case TRUNCATE -> parseTruncate();
            case EXPLAIN -> parseExplain();
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

        boolean distinct = false;
        if (peek().type() == TokenType.DISTINCT) {
            distinct = true;
            advance();
        }

        List<String> columns = new ArrayList<>();
        List<SelectStatement.SelectItem> items = new ArrayList<>();
        if (peek().type() == TokenType.STAR) {
            columns.add("*");
            items.add(new SelectStatement.SelectItem(new Expression.Literal("*", "*"), "*"));
            advance();
        } else {
            items.add(parseSelectItem());
            columns.add(items.get(items.size() - 1).label());
            while (peek().type() == TokenType.COMMA) {
                advance();
                items.add(parseSelectItem());
                columns.add(items.get(items.size() - 1).label());
            }
        }

        expect(TokenType.FROM);
        SelectStatement.TableRef mainTable = parseTableRef();
        String tableName = mainTable.tableName();

        // JOIN 链：[INNER] JOIN / LEFT [OUTER] JOIN ... ON ...
        List<SelectStatement.JoinSpec> joins = new ArrayList<>();
        while (peek().type() == TokenType.JOIN || peek().type() == TokenType.INNER
                || peek().type() == TokenType.LEFT) {
            String type;
            if (peek().type() == TokenType.INNER) {
                advance();
                expect(TokenType.JOIN);
                type = "INNER";
            } else if (peek().type() == TokenType.LEFT) {
                advance();
                expectOptional(TokenType.OUTER);
                expect(TokenType.JOIN);
                type = "LEFT";
            } else {
                advance();
                type = "INNER";
            }
            SelectStatement.TableRef right = parseTableRef();
            expect(TokenType.ON);
            WhereClause on = new WhereClause(parseOrExpression());
            joins.add(new SelectStatement.JoinSpec(type, right, on));
        }

        WhereClause whereClause = null;
        if (peek().type() == TokenType.WHERE) {
            whereClause = parseWhereClause();
        }

        List<String> groupBy = new ArrayList<>();
        if (peek().type() == TokenType.GROUP) {
            advance();
            expect(TokenType.BY);
            groupBy.add(parseIdentifier());
            while (peek().type() == TokenType.COMMA) {
                advance();
                groupBy.add(parseIdentifier());
            }
        }

        WhereClause havingClause = null;
        if (peek().type() == TokenType.HAVING) {
            advance();
            havingClause = new WhereClause(parseOrExpression());
        }

        List<SelectStatement.OrderBy> orderBy = new ArrayList<>();
        if (peek().type() == TokenType.ORDER) {
            advance();
            expect(TokenType.BY);
            orderBy.add(parseOrderByItem());
            while (peek().type() == TokenType.COMMA) {
                advance();
                orderBy.add(parseOrderByItem());
            }
        }

        Integer limit = null;
        Integer offset = null;
        if (peek().type() == TokenType.LIMIT) {
            advance();
            limit = parseIntLiteral();
            if (peek().type() == TokenType.COMMA) {
                // MySQL 风格：LIMIT offset, count
                advance();
                offset = limit;
                limit = parseIntLiteral();
            } else if (peek().type() == TokenType.OFFSET) {
                advance();
                offset = parseIntLiteral();
            }
        }

        expectOptional(TokenType.SEMICOLON);
        return new SelectStatement(columns, tableName, mainTable, joins, whereClause, items, groupBy,
                havingClause, distinct, orderBy, limit, offset);
    }

    /** FROM 中的表引用：表名 + 可选别名（AS alias 或裸标识符） */
    private SelectStatement.TableRef parseTableRef() {
        String tableName = parseIdentifier();
        String alias = null;
        if (peek().type() == TokenType.AS) {
            advance();
            alias = parseIdentifier();
        } else if (peek().type() == TokenType.IDENTIFIER) {
            alias = advance().value(); // 裸别名（MySQL 风格）
        }
        return new SelectStatement.TableRef(tableName, alias);
    }

    private SelectStatement.SelectItem parseSelectItem() {
        Expression expr = parseExpression();
        String label = expr.sqlText();
        if (peek().type() == TokenType.AS) {
            advance();
            label = parseIdentifier();
        }
        return new SelectStatement.SelectItem(expr, label);
    }

    /** 支持把数据类型关键字等用作列名的逻辑与 parseIdentifier 保持一致 */
    private String tokenAsIdentifier(Token t) {
        if (t.type() == TokenType.IDENTIFIER || isDataTypeToken(t.type())
                || t.type() == TokenType.INDEX || t.type() == TokenType.INDEXES || t.type() == TokenType.TABLES) {
            return t.value();
        }
        throw new ParseException("Expected identifier, got: " + t.value() + " (" + t.type() + ")");
    }

    private SelectStatement.OrderBy parseOrderByItem() {
        String column;
        Token first = advance();
        if (first.type() == TokenType.IDENTIFIER && peek().type() == TokenType.LPAREN
                && Expression.AGGREGATE_FUNCTIONS.contains(first.value().toUpperCase(Locale.ROOT))) {
            // ORDER BY COUNT(*) / SUM(price) 等，与输出列 label 对应
            String fn = first.value().toUpperCase(Locale.ROOT);
            advance(); // LPAREN
            String col;
            if (peek().type() == TokenType.STAR) {
                advance();
                col = "*";
            } else {
                col = parseIdentifier();
            }
            expect(TokenType.RPAREN);
            column = fn + "(" + col + ")";
        } else {
            column = tokenAsIdentifier(first);
        }
        boolean ascending = true;
        if (peek().type() == TokenType.ASC) {
            advance();
        } else if (peek().type() == TokenType.DESC) {
            ascending = false;
            advance();
        }
        return new SelectStatement.OrderBy(column, ascending);
    }

    private int parseIntLiteral() {
        Token t = peek();
        if (t.type() == TokenType.NUMBER) {
            advance();
            try {
                return Integer.parseInt(t.value());
            } catch (NumberFormatException e) {
                throw new ParseException("Invalid number: " + t.value());
            }
        }
        throw new ParseException("Expected number, got: " + t.value());
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

        List<List<String>> valueRows = new ArrayList<>();
        valueRows.add(parseValueGroup());
        while (peek().type() == TokenType.COMMA) {
            advance();
            valueRows.add(parseValueGroup());
        }

        expectOptional(TokenType.SEMICOLON);
        return new InsertStatement(tableName, columns, valueRows);
    }

    private List<String> parseValueGroup() {
        expect(TokenType.LPAREN);
        List<String> values = new ArrayList<>();
        values.add(parseValue());
        while (peek().type() == TokenType.COMMA) {
            advance();
            values.add(parseValue());
        }
        expect(TokenType.RPAREN);
        return values;
    }

    // === UPDATE ===

    private UpdateStatement parseUpdate() {
        expect(TokenType.UPDATE);
        String tableName = parseIdentifier();
        expect(TokenType.SET);

        Map<String, Expression> assignments = new LinkedHashMap<>();
        String col = parseIdentifier();
        expect(TokenType.EQUALS);
        assignments.put(col, parseExpression());
        while (peek().type() == TokenType.COMMA) {
            advance();
            String c = parseIdentifier();
            expect(TokenType.EQUALS);
            assignments.put(c, parseExpression());
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
        boolean ifNotExists = false;
        if (peek().type() == TokenType.IF) {
            advance();
            expect(TokenType.NOT);
            expect(TokenType.EXISTS);
            ifNotExists = true;
        }
        String tableName = parseIdentifier();
        expect(TokenType.LPAREN);

        List<ColumnDefinition> columns = new ArrayList<>();
        columns.add(parseColumnDef());
        while (peek().type() == TokenType.COMMA) {
            advance();
            // 表级约束：PRIMARY KEY (col, ...) / UNIQUE (col)
            if (peek().type() == TokenType.PRIMARY) {
                advance();
                expect(TokenType.KEY);
                expect(TokenType.LPAREN);
                List<String> pkCols = new ArrayList<>();
                pkCols.add(parseIdentifier());
                while (peek().type() == TokenType.COMMA) {
                    advance();
                    pkCols.add(parseIdentifier());
                }
                expect(TokenType.RPAREN);
                for (String pkCol : pkCols) {
                    ColumnDefinition cd = findColumnDef(columns, pkCol);
                    if (cd == null) {
                        throw new ParseException("PRIMARY KEY column '" + pkCol + "' not defined");
                    }
                    cd.addConstraint("PRIMARY KEY");
                }
            } else if (peek().type() == TokenType.UNIQUE) {
                advance();
                expect(TokenType.LPAREN);
                String uCol = parseIdentifier();
                expect(TokenType.RPAREN);
                ColumnDefinition cd = findColumnDef(columns, uCol);
                if (cd == null) {
                    throw new ParseException("UNIQUE column '" + uCol + "' not defined");
                }
                cd.addConstraint("UNIQUE");
            } else {
                columns.add(parseColumnDef());
            }
        }
        expect(TokenType.RPAREN);
        expectOptional(TokenType.SEMICOLON);

        CreateTableStatement stmt = new CreateTableStatement(tableName, columns);
        stmt.ifNotExists = ifNotExists;
        return stmt;
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
        boolean ifExists = false;
        if (peek().type() == TokenType.IF) {
            advance();
            expect(TokenType.EXISTS);
            ifExists = true;
        }
        String tableName = parseIdentifier();
        expectOptional(TokenType.SEMICOLON);
        return new DropTableStatement(tableName, ifExists);
    }

    // === EXPLAIN ===

    private ExplainStatement parseExplain() {
        expect(TokenType.EXPLAIN);
        Statement inner = parse();
        if (!(inner instanceof SelectStatement) && !(inner instanceof UpdateStatement)
                && !(inner instanceof DeleteStatement)) {
            throw new ParseException("EXPLAIN only supports SELECT, UPDATE or DELETE");
        }
        return new ExplainStatement(inner);
    }

    // === TRUNCATE ===

    private TruncateStatement parseTruncate() {
        expect(TokenType.TRUNCATE);
        expectOptional(TokenType.TABLE); // TABLE 关键字可选（与 MySQL 一致）
        String tableName = parseIdentifier();
        expectOptional(TokenType.SEMICOLON);
        return new TruncateStatement(tableName);
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
        } else if (peek().type() == TokenType.CREATE) {
            advance();
            expect(TokenType.TABLE);
            String tableName = parseIdentifier();
            expectOptional(TokenType.SEMICOLON);
            return new ShowCreateTableStatement(tableName);
        } else if (peek().type() == TokenType.INDEXES) {
            advance();
            expect(TokenType.FROM);
            String tableName = parseIdentifier();
            expectOptional(TokenType.SEMICOLON);
            return new ShowIndexesStatement(tableName);
        }
        throw new ParseException("Expected TABLES, CREATE TABLE or INDEXES after SHOW, got: " + peek().value());
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
        return new WhereClause(parseOrExpression());
    }

    /** OR 优先级最低：a OR b AND c == a OR (b AND c) */
    private WhereClause.Node parseOrExpression() {
        WhereClause.Node left = parseAndExpression();
        while (peek().type() == TokenType.OR) {
            advance();
            left = new WhereClause.OrNode(left, parseAndExpression());
        }
        return left;
    }

    private WhereClause.Node parseAndExpression() {
        WhereClause.Node left = parseNotExpression();
        while (peek().type() == TokenType.AND) {
            advance();
            left = new WhereClause.AndNode(left, parseNotExpression());
        }
        return left;
    }

    private WhereClause.Node parseNotExpression() {
        if (peek().type() == TokenType.NOT) {
            advance();
            return new WhereClause.NotNode(parseNotExpression());
        }
        return parsePrimaryExpression();
    }

    private WhereClause.Node parsePrimaryExpression() {
        if (peek().type() == TokenType.LPAREN) {
            advance();
            WhereClause.Node inner = parseOrExpression();
            expect(TokenType.RPAREN);
            return inner;
        }
        return parseCondition();
    }

    private WhereClause.Node parseCondition() {
        Expression left = parseConditionOperand();

        if (peek().type() == TokenType.IS) {
            advance();
            boolean negated = false;
            if (peek().type() == TokenType.NOT) {
                negated = true;
                advance();
            }
            expect(TokenType.NULL_KW);
            return new WhereClause.NullCondition(left, negated);
        }

        if (peek().type() == TokenType.LIKE) {
            advance();
            return new WhereClause.LikeCondition(left, parseValue());
        }

        if (peek().type() == TokenType.BETWEEN) {
            advance();
            String low = parseValue();
            expect(TokenType.AND);
            String high = parseValue();
            return new WhereClause.BetweenCondition(left, low, high, false);
        }

        boolean negatedIn = false;
        if (peek().type() == TokenType.NOT) {
            advance();
            if (peek().type() == TokenType.IN) {
                advance();
                negatedIn = true;
            } else if (peek().type() == TokenType.LIKE) {
                advance();
                return new WhereClause.LikeCondition(left, parseValue(), true);
            } else if (peek().type() == TokenType.BETWEEN) {
                advance();
                String low = parseValue();
                expect(TokenType.AND);
                String high = parseValue();
                return new WhereClause.BetweenCondition(left, low, high, true);
            } else {
                throw new ParseException("Expected IN, LIKE or BETWEEN after NOT, got: " + peek().value());
            }
        } else if (peek().type() == TokenType.IN) {
            advance();
        } else {
            String operator = parseComparisonOp();
            Expression right = parseExpression();
            return new WhereClause.OpCondition(left, operator, right);
        }

        expect(TokenType.LPAREN);
        // IN 子查询 (SELECT ...)
        if (peek().type() == TokenType.SELECT) {
            SelectStatement sub = parseSelect();
            expect(TokenType.RPAREN);
            return new WhereClause.InSubqueryCondition(left, sub, negatedIn);
        }
        List<String> values = new ArrayList<>();
        values.add(parseValue());
        while (peek().type() == TokenType.COMMA) {
            advance();
            values.add(parseValue());
        }
        expect(TokenType.RPAREN);
        return new WhereClause.InCondition(left, values, negatedIn);
    }

    /** 条件左侧操作数：完整表达式（列引用、函数、算术）；裸字面量无意义，直接报错 */
    private Expression parseConditionOperand() {
        Expression e = parseExpression();
        if (e instanceof Expression.Literal) {
            throw new ParseException("Expected column or function before operator, got: " + e.sqlText());
        }
        return e;
    }

    // === 表达式 ===

    /** 加减法（优先级最低） */
    private Expression parseExpression() {
        Expression left = parseMultiplicative();
        while (peek().type() == TokenType.PLUS || peek().type() == TokenType.MINUS) {
            String op = advance().value();
            left = new Expression.Arith(op, left, parseMultiplicative());
        }
        return left;
    }

    /** 乘除取模 */
    private Expression parseMultiplicative() {
        Expression left = parseUnary();
        while (peek().type() == TokenType.STAR || peek().type() == TokenType.SLASH
                || peek().type() == TokenType.PERCENT) {
            String op = advance().value();
            left = new Expression.Arith(op, left, parseUnary());
        }
        return left;
    }

    private Expression parseUnary() {
        if (peek().type() == TokenType.MINUS) {
            advance();
            Token t = peek();
            if (t.type() == TokenType.NUMBER) {
                advance();
                return numberLiteral("-" + t.value());
            }
            return new Expression.Arith("-", new Expression.Literal(0L, "0"), parseUnary());
        }
        if (peek().type() == TokenType.PLUS) {
            advance();
            return parseUnary();
        }
        return parsePrimary();
    }

    private Expression parsePrimary() {
        Token t = peek();
        switch (t.type()) {
            case STRING:
                advance();
                return new Expression.Literal(t.value(), "'" + t.value() + "'");
            case NUMBER:
                advance();
                return numberLiteral(t.value());
            case NULL_KW:
                advance();
                return new Expression.Literal(null, "null");
            case STAR:
                advance();
                return new Expression.Literal("*", "*");
            case LPAREN: {
                advance();
                // 标量子查询 (SELECT ...)
                if (peek().type() == TokenType.SELECT) {
                    SelectStatement sub = parseSelect();
                    expect(TokenType.RPAREN);
                    return new SubqueryExpression(sub);
                }
                Expression inner = parseExpression();
                expect(TokenType.RPAREN);
                return inner;
            }
            default:
                if (t.type() == TokenType.IDENTIFIER || isDataTypeToken(t.type())
                        || t.type() == TokenType.INDEX || t.type() == TokenType.INDEXES
                        || t.type() == TokenType.TABLES) {
                    advance();
                    // 函数调用
                    if (peek().type() == TokenType.LPAREN) {
                        return parseFunctionCall(t.value());
                    }
                    // 限定列名 alias.column / table.column
                    if (peek().type() == TokenType.DOT) {
                        advance();
                        String col = parseIdentifier();
                        return new Expression.ColumnRef(t.value() + "." + col);
                    }
                    // TRUE / FALSE 布尔字面量
                    String lower = t.value().toLowerCase(Locale.ROOT);
                    if (lower.equals("true") || lower.equals("false")) {
                        return new Expression.Literal(Boolean.parseBoolean(lower), t.value());
                    }
                    return new Expression.ColumnRef(t.value());
                }
                throw new ParseException("Unexpected token in expression: " + t.value() + " (" + t.type() + ")");
        }
    }

    private Expression parseFunctionCall(String name) {
        String fn = name.toUpperCase(Locale.ROOT);
        if (!Expression.AGGREGATE_FUNCTIONS.contains(fn) && !Expression.SCALAR_FUNCTIONS.contains(fn)) {
            throw new ParseException("Unknown function: " + name);
        }
        expect(TokenType.LPAREN);

        List<Expression> args = new ArrayList<>();
        if (peek().type() == TokenType.STAR) {
            advance();
            args.add(new Expression.Literal("*", "*"));
        } else if (peek().type() != TokenType.RPAREN) {
            args.add(parseExpression());
            while (peek().type() == TokenType.COMMA) {
                advance();
                args.add(parseExpression());
            }
        }
        expect(TokenType.RPAREN);

        // 参数校验
        if (Expression.AGGREGATE_FUNCTIONS.contains(fn)) {
            if (args.size() != 1) {
                throw new ParseException(fn + "() expects exactly one argument");
            }
            Expression arg = args.get(0);
            if (arg instanceof Expression.Literal l && "*".equals(l.typed)) {
                if (!fn.equals("COUNT")) {
                    throw new ParseException("* is only allowed in COUNT(*)");
                }
            } else if (!(arg instanceof Expression.ColumnRef)) {
                throw new ParseException("Aggregate argument must be a column name or *: " + fn);
            }
        } else {
            int min = fn.equals("COALESCE") ? 1 : fn.equals("ROUND") ? 1 : 1;
            int max = fn.equals("ROUND") ? 2 : fn.equals("COALESCE") ? Integer.MAX_VALUE : 1;
            if (args.size() < min || args.size() > max) {
                throw new ParseException(fn + "() expects " + (max == Integer.MAX_VALUE
                        ? "at least " + min : min == max ? min : min + ".." + max) + " argument(s)");
            }
        }
        return new Expression.Func(fn, args);
    }

    /** 数字字面量：整数 → Long，小数 → Double */
    private Expression numberLiteral(String raw) {
        if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
            return new Expression.Literal(Double.parseDouble(raw), raw);
        }
        try {
            return new Expression.Literal(Long.parseLong(raw), raw);
        } catch (NumberFormatException e) {
            return new Expression.Literal(Double.parseDouble(raw), raw);
        }
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
            } else if (peek().type() == TokenType.AUTO_INCREMENT) {
                advance();
                cd.setAutoIncrement(true);
            } else if (peek().type() == TokenType.DEFAULT) {
                advance();
                cd.setDefaultValue(parseValue());
            } else if (peek().type() == TokenType.UNIQUE) {
                advance();
                cd.addConstraint("UNIQUE");
            } else if (peek().type() == TokenType.REFERENCES) {
                advance();
                cd.referencesTable = parseIdentifier();
                if (peek().type() == TokenType.LPAREN) {
                    advance();
                    cd.referencesColumn = parseIdentifier();
                    expect(TokenType.RPAREN);
                }
            } else {
                break;
            }
        }
        return cd;
    }

    private static ColumnDefinition findColumnDef(List<ColumnDefinition> columns, String name) {
        for (ColumnDefinition cd : columns) {
            if (cd.name.equalsIgnoreCase(name)) return cd;
        }
        return null;
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
