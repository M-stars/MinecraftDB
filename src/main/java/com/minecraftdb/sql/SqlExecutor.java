package com.minecraftdb.sql;

import com.minecraftdb.database.DatabaseManager;
import com.minecraftdb.database.SchemaManager;
import com.minecraftdb.database.TableManager;
import com.minecraftdb.database.TransactionManager;
import com.minecraftdb.sql.ast.*;

import java.util.*;

public class SqlExecutor {

    private final DatabaseManager dbManager;
    private final String databaseName;

    public SqlExecutor(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        this.databaseName = dbManager.getDatabaseName();
    }

    public QueryResult execute(String sql) {
        SqlLexer lexer = new SqlLexer(sql);
        List<SqlLexer.Token> tokens = lexer.tokenize();
        SqlParser parser = new SqlParser(tokens);
        Statement stmt = parser.parse();
        return execute(stmt);
    }

    public QueryResult execute(Statement stmt) {
        return switch (stmt.getType()) {
            case CREATE_TABLE -> executeCreateTable((CreateTableStatement) stmt);
            case DROP_TABLE -> executeDropTable((DropTableStatement) stmt);
            case ALTER_TABLE -> executeAlterTable((AlterTableStatement) stmt);
            case INSERT -> executeInsert((InsertStatement) stmt);
            case SELECT -> executeSelect((SelectStatement) stmt);
            case UPDATE -> executeUpdate((UpdateStatement) stmt);
            case DELETE -> executeDelete((DeleteStatement) stmt);
            case SHOW_TABLES -> executeShowTables();
            case DESC_TABLE -> executeDescTable((DescTableStatement) stmt);
            case BEGIN -> executeBegin();
            case COMMIT -> executeCommit();
            case ROLLBACK -> executeRollback();
            case CREATE_INDEX -> executeCreateIndex((CreateIndexStatement) stmt);
            case SHOW_INDEXES -> executeShowIndexes((ShowIndexesStatement) stmt);
        };
    }

    private QueryResult executeCreateTable(CreateTableStatement stmt) {
        TableManager tableManager = dbManager.getTableManager();
        SchemaManager schemaManager = dbManager.getSchemaManager();

        if (tableManager.tableExists(stmt.tableName)) {
            return QueryResult.error("Table '" + stmt.tableName + "' already exists");
        }

        tableManager.createTable(stmt.tableName, stmt.columns);
        schemaManager.saveSchema(stmt.tableName, stmt.columns);
        return QueryResult.ok("Table '" + stmt.tableName + "' created with " + stmt.columns.size() + " column(s)");
    }

    private QueryResult executeDropTable(DropTableStatement stmt) {
        TableManager tableManager = dbManager.getTableManager();

        if (!tableManager.tableExists(stmt.tableName)) {
            return QueryResult.error("Table '" + stmt.tableName + "' does not exist");
        }

        tableManager.dropTable(stmt.tableName);
        dbManager.getSchemaManager().removeSchema(stmt.tableName);
        return QueryResult.ok("Table '" + stmt.tableName + "' dropped");
    }

    private QueryResult executeAlterTable(AlterTableStatement stmt) {
        TableManager tableManager = dbManager.getTableManager();
        SchemaManager schemaManager = dbManager.getSchemaManager();

        if (!tableManager.tableExists(stmt.tableName)) {
            return QueryResult.error("Table '" + stmt.tableName + "' does not exist");
        }

        List<ColumnDefinition> schema = schemaManager.getSchema(stmt.tableName);
        if (schema == null) {
            return QueryResult.error("Schema not found for table '" + stmt.tableName + "'");
        }

        if ("ADD".equals(stmt.action)) {
            schema.add(stmt.column);
            schemaManager.saveSchema(stmt.tableName, schema);
            return QueryResult.ok("Column '" + stmt.column.name + "' added to table '" + stmt.tableName + "'");
        } else if ("DROP".equals(stmt.action)) {
            boolean removed = schema.removeIf(c -> c.name.equalsIgnoreCase(stmt.dropColumnName));
            if (!removed) {
                return QueryResult.error("Column '" + stmt.dropColumnName + "' not found in table '" + stmt.tableName + "'");
            }
            schemaManager.saveSchema(stmt.tableName, schema);
            return QueryResult.ok("Column '" + stmt.dropColumnName + "' dropped from table '" + stmt.tableName + "'");
        }

        return QueryResult.error("Unknown ALTER action: " + stmt.action);
    }

    private QueryResult executeInsert(InsertStatement stmt) {
        TableManager tableManager = dbManager.getTableManager();
        SchemaManager schemaManager = dbManager.getSchemaManager();
        TransactionManager txnManager = dbManager.getTransactionManager();

        if (!tableManager.tableExists(stmt.tableName)) {
            return QueryResult.error("Table '" + stmt.tableName + "' does not exist");
        }

        List<ColumnDefinition> schema = schemaManager.getSchema(stmt.tableName);
        if (schema == null) {
            return QueryResult.error("Schema not found for table '" + stmt.tableName + "'");
        }

        // Resolve columns
        List<String> targetColumns;
        if (stmt.columns.isEmpty()) {
            targetColumns = new ArrayList<>();
            for (ColumnDefinition cd : schema) {
                targetColumns.add(cd.name);
            }
        } else {
            targetColumns = stmt.columns;
        }

        // 逐组构建行数据（先全部校验，任何一组有问题都整体失败，不写入半截数据）
        List<Map<String, Object>> allRowData = new ArrayList<>();
        for (List<String> valueGroup : stmt.valueRows) {
            if (targetColumns.size() != valueGroup.size()) {
                return QueryResult.error("Column count (" + targetColumns.size()
                        + ") does not match value count (" + valueGroup.size() + ")");
            }

            Map<String, Object> rowData = new LinkedHashMap<>();
            for (int i = 0; i < targetColumns.size(); i++) {
                String colName = targetColumns.get(i);
                String rawValue = valueGroup.get(i);

                ColumnDefinition colDef = findColumn(schema, colName);
                if (colDef == null) {
                    return QueryResult.error("Column '" + colName + "' not found in table '" + stmt.tableName + "'");
                }

                Object parsedValue = parseValue(rawValue, colDef.dataType);
                rowData.put(colName, parsedValue);
            }
            allRowData.add(rowData);
        }

        // Auto-commit mode: wrap in transaction
        boolean autoCommit = !txnManager.isInTransaction();
        if (autoCommit) {
            txnManager.begin();
        }

        try {
            for (Map<String, Object> rowData : allRowData) {
                tableManager.insertRow(stmt.tableName, schema, rowData);
            }
            if (autoCommit) {
                txnManager.commit();
            }
        } catch (Exception e) {
            if (autoCommit) {
                txnManager.rollback();
            }
            tableManager.getBlockOperator().clearPending();
            return QueryResult.error("Insert failed: " + e.getMessage());
        }

        return QueryResult.ok(allRowData.size() + " row(s) inserted into '" + stmt.tableName + "'");
    }

    private QueryResult executeSelect(SelectStatement stmt) {
        TableManager tableManager = dbManager.getTableManager();
        SchemaManager schemaManager = dbManager.getSchemaManager();

        if (!tableManager.tableExists(stmt.tableName)) {
            return QueryResult.error("Table '" + stmt.tableName + "' does not exist");
        }

        List<ColumnDefinition> schema = schemaManager.getSchema(stmt.tableName);
        if (schema == null) {
            return QueryResult.error("Schema not found for table '" + stmt.tableName + "'");
        }

        List<String> columnNames = new ArrayList<>();
        for (ColumnDefinition cd : schema) {
            columnNames.add(cd.name);
        }

        List<TableManager.RowWithZ> allRows = tableManager.readAllRows(stmt.tableName, schema);

        // Apply WHERE filter
        List<Map<String, Object>> filteredRows = new ArrayList<>();
        for (TableManager.RowWithZ rowWithZ : allRows) {
            Map<String, Object> row = rowWithZ.values();
            if (row == null || row.isEmpty()) continue;
            if (stmt.whereClause == null) {
                filteredRows.add(row);
            } else {
                List<Object> rowValues = new ArrayList<>();
                for (String col : columnNames) {
                    rowValues.add(row.get(col));
                }
                if (stmt.whereClause.evaluate(columnNames, rowValues)) {
                    filteredRows.add(row);
                }
            }
        }

        // ORDER BY（列名统一为 schema 内的真实列名，找不到直接报错）
        List<SelectStatement.OrderBy> resolvedOrderBy = new ArrayList<>();
        for (SelectStatement.OrderBy ob : stmt.orderBy) {
            ColumnDefinition cd = findColumn(schema, ob.column());
            if (cd == null) {
                return QueryResult.error("Unknown column '" + ob.column() + "' in ORDER BY");
            }
            resolvedOrderBy.add(new SelectStatement.OrderBy(cd.name, ob.ascending()));
        }
        if (!resolvedOrderBy.isEmpty()) {
            filteredRows.sort((a, b) -> {
                for (SelectStatement.OrderBy ob : resolvedOrderBy) {
                    int cmp = compareValues(a.get(ob.column()), b.get(ob.column()));
                    if (cmp != 0) return ob.ascending() ? cmp : -cmp;
                }
                return 0;
            });
        }

        // LIMIT / OFFSET
        int from = stmt.offset != null ? Math.min(stmt.offset, filteredRows.size()) : 0;
        int to = stmt.limit != null ? Math.min(from + stmt.limit, filteredRows.size()) : filteredRows.size();
        List<Map<String, Object>> pagedRows = filteredRows.subList(from, to);

        // Project columns
        List<String> selectColumns = stmt.isSelectAll() ? columnNames : stmt.columns;

        QueryResult result = QueryResult.ok("Selected " + pagedRows.size() + " row(s)");
        result.setColumns(selectColumns);

        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : pagedRows) {
            List<Object> projected = new ArrayList<>();
            for (String col : selectColumns) {
                projected.add(row.getOrDefault(col, null));
            }
            rows.add(projected);
        }
        result.setRows(rows);
        result.setRowCount(pagedRows.size());

        return result;
    }

    /** 排序比较：null 最小，数值按数值比较，其余按字符串比较 */
    private int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        return a.toString().compareTo(b.toString());
    }

    private QueryResult executeUpdate(UpdateStatement stmt) {
        TableManager tableManager = dbManager.getTableManager();
        SchemaManager schemaManager = dbManager.getSchemaManager();
        TransactionManager txnManager = dbManager.getTransactionManager();

        if (!tableManager.tableExists(stmt.tableName)) {
            return QueryResult.error("Table '" + stmt.tableName + "' does not exist");
        }

        List<ColumnDefinition> schema = schemaManager.getSchema(stmt.tableName);
        if (schema == null) {
            return QueryResult.error("Schema not found");
        }

        List<String> columnNames = new ArrayList<>();
        for (ColumnDefinition cd : schema) {
            columnNames.add(cd.name);
        }

        List<TableManager.RowWithZ> allRows = tableManager.readAllRows(stmt.tableName, schema);

        int updated = 0;
        boolean autoCommit = !txnManager.isInTransaction();
        if (autoCommit) txnManager.begin();

        try {
            for (TableManager.RowWithZ rowWithZ : allRows) {
                Map<String, Object> row = rowWithZ.values();
                if (row == null || row.isEmpty()) continue;

                boolean matches = true;
                if (stmt.whereClause != null) {
                    List<Object> rowValues = new ArrayList<>();
                    for (String col : columnNames) {
                        rowValues.add(row.get(col));
                    }
                    matches = stmt.whereClause.evaluate(columnNames, rowValues);
                }

                if (matches) {
                    for (Map.Entry<String, String> assign : stmt.assignments.entrySet()) {
                        ColumnDefinition colDef = findColumn(schema, assign.getKey());
                        if (colDef != null) {
                            Object parsedValue = parseValue(assign.getValue(), colDef.dataType);
                            row.put(assign.getKey(), parsedValue);
                        }
                    }
                    tableManager.updateRow(stmt.tableName, schema, rowWithZ.z(), row);
                    updated++;
                }
            }

            if (autoCommit) txnManager.commit();
        } catch (Exception e) {
            if (autoCommit) txnManager.rollback();
            tableManager.getBlockOperator().clearPending();
            return QueryResult.error("Update failed: " + e.getMessage());
        }

        return QueryResult.ok(updated + " row(s) updated in '" + stmt.tableName + "'");
    }

    private QueryResult executeDelete(DeleteStatement stmt) {
        TableManager tableManager = dbManager.getTableManager();
        SchemaManager schemaManager = dbManager.getSchemaManager();
        TransactionManager txnManager = dbManager.getTransactionManager();

        if (!tableManager.tableExists(stmt.tableName)) {
            return QueryResult.error("Table '" + stmt.tableName + "' does not exist");
        }

        List<ColumnDefinition> schema = schemaManager.getSchema(stmt.tableName);
        if (schema == null) {
            return QueryResult.error("Schema not found");
        }

        List<String> columnNames = new ArrayList<>();
        for (ColumnDefinition cd : schema) {
            columnNames.add(cd.name);
        }

        // For deletion, we mark rows as deleted (set to air)
        List<TableManager.RowWithZ> allRows = tableManager.readAllRows(stmt.tableName, schema);
        List<Long> toDelete = new ArrayList<>();

        for (TableManager.RowWithZ rowWithZ : allRows) {
            Map<String, Object> row = rowWithZ.values();
            if (row == null || row.isEmpty()) continue;

            boolean matches = true;
            if (stmt.whereClause != null) {
                List<Object> rowValues = new ArrayList<>();
                for (String col : columnNames) {
                    rowValues.add(row.get(col));
                }
                matches = stmt.whereClause.evaluate(columnNames, rowValues);
            }

            if (matches) {
                toDelete.add(rowWithZ.z());
            }
        }

        boolean autoCommit = !txnManager.isInTransaction();
        if (autoCommit) txnManager.begin();

        try {
            for (long z : toDelete) {
                tableManager.deleteRow(stmt.tableName, schema, z);
            }
            if (autoCommit) txnManager.commit();
        } catch (Exception e) {
            if (autoCommit) txnManager.rollback();
            tableManager.getBlockOperator().clearPending();
            return QueryResult.error("Delete failed: " + e.getMessage());
        }

        return QueryResult.ok(toDelete.size() + " row(s) deleted from '" + stmt.tableName + "'");
    }

    private QueryResult executeShowTables() {
        Set<String> tables = dbManager.getTableManager().listTables();
        String colName = "Tables_in_" + databaseName;
        List<String> cols = List.of(colName);
        List<List<Object>> rows = new ArrayList<>();
        for (String t : tables) {
            rows.add(List.of(t));
        }
        QueryResult result = QueryResult.ok("数据库 '" + databaseName + "' 中有 " + tables.size() + " 张表");
        result.setColumns(cols);
        result.setRows(rows);
        result.setRowCount(tables.size());
        return result;
    }

    private QueryResult executeDescTable(DescTableStatement stmt) {
        List<ColumnDefinition> schema = dbManager.getSchemaManager().getSchema(stmt.tableName);
        if (schema == null) {
            return QueryResult.error("Table '" + stmt.tableName + "' does not exist");
        }

        List<String> cols = List.of("Field", "Type", "Null", "Key");
        List<List<Object>> rows = new ArrayList<>();
        for (ColumnDefinition cd : schema) {
            rows.add(List.of(
                    cd.name,
                    cd.dataType,
                    cd.isNullable() ? "YES" : "NO",
                    cd.isPrimaryKey() ? "PRI" : ""
            ));
        }

        QueryResult result = QueryResult.ok(schema.size() + " column(s)");
        result.setColumns(cols);
        result.setRows(rows);
        result.setRowCount(schema.size());
        return result;
    }

    private QueryResult executeBegin() {
        dbManager.getTransactionManager().begin();
        return QueryResult.ok("Transaction started");
    }

    private QueryResult executeCommit() {
        dbManager.getTransactionManager().commit();
        return QueryResult.ok("Transaction committed");
    }

    private QueryResult executeRollback() {
        dbManager.getTransactionManager().rollback();
        return QueryResult.ok("Transaction rolled back");
    }

    private QueryResult executeCreateIndex(CreateIndexStatement stmt) {
        dbManager.getSchemaManager().addIndex(stmt.tableName, stmt.indexName, stmt.columnName);
        return QueryResult.ok("Index '" + stmt.indexName + "' created on '" + stmt.tableName + "(" + stmt.columnName + ")'");
    }

    private QueryResult executeShowIndexes(ShowIndexesStatement stmt) {
        Map<String, String> indexes = dbManager.getSchemaManager().getIndexes(stmt.tableName);
        List<String> cols = List.of("Index", "Column");
        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : indexes.entrySet()) {
            rows.add(List.of(e.getKey(), e.getValue()));
        }
        QueryResult result = QueryResult.ok(indexes.size() + " index(es)");
        result.setColumns(cols);
        result.setRows(rows);
        result.setRowCount(indexes.size());
        return result;
    }

    // === Helpers ===

    private ColumnDefinition findColumn(List<ColumnDefinition> schema, String name) {
        for (ColumnDefinition cd : schema) {
            if (cd.name.equalsIgnoreCase(name)) return cd;
        }
        return null;
    }

    private Object parseValue(String raw, String dataType) {
        if (raw == null || "null".equalsIgnoreCase(raw)) return null;

        String typeBase = dataType.split("\\(")[0].toUpperCase();

        return switch (typeBase) {
            case "INT", "INTEGER" -> {
                try { yield Integer.parseInt(raw); } catch (NumberFormatException e) { yield 0; }
            }
            case "BIGINT" -> {
                try { yield Long.parseLong(raw); } catch (NumberFormatException e) { yield 0L; }
            }
            case "FLOAT" -> {
                try { yield Float.parseFloat(raw); } catch (NumberFormatException e) { yield 0.0f; }
            }
            case "DOUBLE" -> {
                try { yield Double.parseDouble(raw); } catch (NumberFormatException e) { yield 0.0; }
            }
            case "BOOLEAN", "BOOL" -> "true".equalsIgnoreCase(raw) || "1".equals(raw);
            case "VARCHAR", "TEXT", "STRING" -> stripQuotes(raw);
            case "DATE", "DATETIME" -> {
                try { yield Long.parseLong(raw); } catch (NumberFormatException e) { yield raw; }
            }
            default -> stripQuotes(raw);
        };
    }

    private String stripQuotes(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // === QueryResult ===

    public static class QueryResult {
        private final boolean success;
        private final String message;
        private List<String> columns;
        private List<List<Object>> rows;
        private int rowCount;

        private QueryResult(boolean success, String message) {
            this.success = success;
            this.message = message;
            this.columns = new ArrayList<>();
            this.rows = new ArrayList<>();
        }

        public static QueryResult ok(String message) {
            return new QueryResult(true, message);
        }

        public static QueryResult error(String message) {
            return new QueryResult(false, message);
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public List<String> getColumns() { return columns; }
        public List<List<Object>> getRows() { return rows; }
        public int getRowCount() { return rowCount; }

        public void setColumns(List<String> columns) { this.columns = columns; }
        public void setRows(List<List<Object>> rows) { this.rows = rows; }
        public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    }
}
