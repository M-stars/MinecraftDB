package com.minecraftdb.sql;

import com.minecraftdb.config.ModConfig;
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
        return execute(sql, null);
    }

    /**
     * 执行 SQL。sessionId 标识客户端会话（玩家 UUID / TCP 连接 / HTTP 会话头），
     * 会话各自拥有独立事务；null 表示服务器控制台。
     */
    public QueryResult execute(String sql, String sessionId) {
        TransactionManager txnManager = dbManager.getTransactionManager();
        txnManager.setCurrentSession(sessionId != null ? sessionId : "console");
        try {
            SqlLexer lexer = new SqlLexer(sql);
            List<SqlLexer.Token> tokens = lexer.tokenize();
            SqlParser parser = new SqlParser(tokens);
            Statement stmt = parser.parse();
            return execute(stmt);
        } finally {
            txnManager.clearCurrentSession();
        }
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
            case SHOW_CREATE_TABLE -> executeShowCreateTable((ShowCreateTableStatement) stmt);
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
        TransactionManager txnManager = dbManager.getTransactionManager();

        if (tableManager.tableExists(stmt.tableName)) {
            if (stmt.ifNotExists) {
                return QueryResult.ok("Table '" + stmt.tableName + "' already exists, skipped (IF NOT EXISTS)");
            }
            return QueryResult.error("Table '" + stmt.tableName + "' already exists");
        }

        long pkCount = stmt.columns.stream().filter(ColumnDefinition::isPrimaryKey).count();
        if (pkCount > 1) {
            return QueryResult.error("Only a single PRIMARY KEY column is supported");
        }

        // DDL 不在事务内（与非事务性 DDL 的常规语义一致），锁在方法内自持
        boolean inTxn = txnManager.isInTransaction();
        txnManager.acquireTableWriteLock(stmt.tableName);
        try {
            tableManager.createTable(stmt.tableName, stmt.columns);
            schemaManager.saveSchema(stmt.tableName, stmt.columns);
        } finally {
            if (!inTxn) txnManager.releaseTableWriteLock(stmt.tableName);
        }
        return QueryResult.ok("Table '" + stmt.tableName + "' created with " + stmt.columns.size() + " column(s)");
    }

    private QueryResult executeDropTable(DropTableStatement stmt) {
        TableManager tableManager = dbManager.getTableManager();
        TransactionManager txnManager = dbManager.getTransactionManager();

        if (!tableManager.tableExists(stmt.tableName)) {
            if (stmt.ifExists) {
                return QueryResult.ok("Table '" + stmt.tableName + "' does not exist, skipped (IF EXISTS)");
            }
            return QueryResult.error("Table '" + stmt.tableName + "' does not exist");
        }

        boolean inTxn = txnManager.isInTransaction();
        txnManager.acquireTableWriteLock(stmt.tableName);
        try {
            tableManager.dropTable(stmt.tableName);
            dbManager.getSchemaManager().removeSchema(stmt.tableName);
        } finally {
            if (!inTxn) txnManager.releaseTableWriteLock(stmt.tableName);
        }
        return QueryResult.ok("Table '" + stmt.tableName + "' dropped");
    }

    private QueryResult executeAlterTable(AlterTableStatement stmt) {
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

        boolean inTxn = txnManager.isInTransaction();
        txnManager.acquireTableWriteLock(stmt.tableName);
        try {
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
        } finally {
            if (!inTxn) txnManager.releaseTableWriteLock(stmt.tableName);
        }
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

        // 先校验列数与列名；行数据在事务内逐组构建——
        // 自增值与唯一性检查依赖前面组已写入的数据
        for (List<String> valueGroup : stmt.valueRows) {
            if (targetColumns.size() != valueGroup.size()) {
                return QueryResult.error("Column count (" + targetColumns.size()
                        + ") does not match value count (" + valueGroup.size() + ")");
            }
            for (int i = 0; i < targetColumns.size(); i++) {
                if (findColumn(schema, targetColumns.get(i)) == null) {
                    return QueryResult.error("Column '" + targetColumns.get(i)
                            + "' not found in table '" + stmt.tableName + "'");
                }
            }
        }

        // Auto-commit mode: wrap in transaction
        boolean autoCommit = !txnManager.isInTransaction();
        if (autoCommit) {
            txnManager.begin();
        }
        // 写锁由 commit/rollback 统一释放（acquire 时已登记进事务）
        txnManager.acquireTableWriteLock(stmt.tableName);

        try {
            int inserted = 0;
            for (List<String> valueGroup : stmt.valueRows) {
                Map<String, Object> rowData = new LinkedHashMap<>();
                for (int i = 0; i < targetColumns.size(); i++) {
                    String colName = targetColumns.get(i);
                    ColumnDefinition colDef = findColumn(schema, colName);
                    Object parsedValue = parseValue(valueGroup.get(i), colDef.dataType);
                    if (parsedValue == null && colDef.isPrimaryKey()) {
                        throw new IllegalStateException("PRIMARY KEY column '" + colDef.name + "' cannot be NULL");
                    }
                    if (parsedValue == null && colDef.autoIncrement) {
                        // MySQL 语义：自增列显式给 NULL 等价于未提供，生成下一个自增值
                        continue;
                    }
                    if (parsedValue != null && (colDef.isPrimaryKey() || colDef.autoIncrement)) {
                        Long dup = findDuplicateZ(stmt.tableName, schema, colDef, parsedValue, -1);
                        if (dup != null) {
                            throw new IllegalStateException("Duplicate value '" + parsedValue
                                    + "' for " + (colDef.isPrimaryKey() ? "PRIMARY KEY" : "AUTO_INCREMENT")
                                    + " column '" + colDef.name + "' (existing row at z=" + dup + ")");
                        }
                    }
                    rowData.put(colName, parsedValue);
                }

                // 补齐未提供的列：自增 > DEFAULT > NULL
                for (ColumnDefinition colDef : schema) {
                    if (rowData.containsKey(colDef.name)) continue;
                    if (colDef.autoIncrement) {
                        rowData.put(colDef.name, nextAutoValue(stmt.tableName, schema, colDef));
                    } else if (colDef.defaultValue != null) {
                        rowData.put(colDef.name, parseValue(colDef.defaultValue, colDef.dataType));
                    } else {
                        rowData.put(colDef.name, null);
                    }
                }

                // NOT NULL / PRIMARY KEY 非空校验（主键隐含 NOT NULL）
                for (ColumnDefinition colDef : schema) {
                    if (rowData.get(colDef.name) == null
                            && (!colDef.isNullable() || colDef.isPrimaryKey())) {
                        throw new IllegalStateException("Column '" + colDef.name
                                + "' cannot be NULL (" + (colDef.isPrimaryKey() ? "PRIMARY KEY" : "NOT NULL")
                                + " constraint)");
                    }
                }

                tableManager.insertRow(stmt.tableName, schema, rowData);
                inserted++;
            }
            if (autoCommit) {
                txnManager.commit();
            }
            return QueryResult.ok(inserted + " row(s) inserted into '" + stmt.tableName + "'");
        } catch (Exception e) {
            if (autoCommit) {
                txnManager.rollback();
            }
            tableManager.getBlockOperator().clearPending();
            return QueryResult.error("Insert failed: " + e.getMessage());
        }
    }

    /** 自增列取现有最大值 +1（扫描该列全部行） */
    private long nextAutoValue(String tableName, List<ColumnDefinition> schema, ColumnDefinition col) {
        TableManager tm = dbManager.getTableManager();
        long max = 0;
        long maxZ = tm.getMaxZ(tableName);
        for (int z = 1; z < maxZ; z++) {
            Map<String, Object> row = tm.readRow(tableName, schema, z);
            if (row == null || row.isEmpty()) continue;
            Object v = row.get(col.name);
            if (v instanceof Number n && n.longValue() > max) {
                max = n.longValue();
            }
        }
        return max + 1;
    }

    /** 在表中查找与 value 相等的行（唯一性检查）；excludeZ 用于 UPDATE 排除本行 */
    private Long findDuplicateZ(String tableName, List<ColumnDefinition> schema, ColumnDefinition col,
                                Object value, long excludeZ) {
        TableManager tm = dbManager.getTableManager();
        long maxZ = tm.getMaxZ(tableName);
        for (int z = 1; z < maxZ; z++) {
            if (z == excludeZ) continue;
            Map<String, Object> row = tm.readRow(tableName, schema, z);
            if (row == null || row.isEmpty()) continue;
            if (valueEquals(value, row.get(col.name))) {
                return (long) z;
            }
        }
        return null;
    }

    private static boolean valueEquals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return a.equals(b);
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

        List<SelectStatement.SelectItem> items = stmt.selectItems;
        if (items == null) { // 旧构造器：从 columns 推导
            items = new ArrayList<>();
            for (String c : stmt.columns) {
                items.add(new SelectStatement.SelectItem(null, c, c));
            }
        }
        boolean aggregateMode = !stmt.groupBy.isEmpty()
                || items.stream().anyMatch(i -> i.function() != null);

        // ORDER BY 解析：优先匹配输出列（SELECT 别名 / 聚合表达式），
        // 否则回退为表列名（允许按未出现在 SELECT 中的列排序）。
        // 两类都存在时：先按表列排物化行，投影后再按输出列稳定排序。
        List<SelectStatement.OrderBy> labelOrderBy = new ArrayList<>();
        List<SelectStatement.OrderBy> columnOrderBy = new ArrayList<>();
        for (SelectStatement.OrderBy ob : stmt.orderBy) {
            String label = null;
            for (SelectStatement.SelectItem item : items) {
                if (item.label().equalsIgnoreCase(ob.column())
                        || item.column().equalsIgnoreCase(ob.column())) {
                    label = item.label();
                    break;
                }
            }
            if (label != null) {
                labelOrderBy.add(new SelectStatement.OrderBy(label, ob.ascending()));
            } else {
                if (aggregateMode) {
                    return QueryResult.error("Unknown column '" + ob.column()
                            + "' in ORDER BY（聚合查询只能按输出列排序）");
                }
                ColumnDefinition cd = findColumn(schema, ob.column());
                if (cd == null) {
                    return QueryResult.error("Unknown column '" + ob.column() + "' in ORDER BY");
                }
                columnOrderBy.add(new SelectStatement.OrderBy(cd.name, ob.ascending()));
            }
        }
        boolean needsSort = !labelOrderBy.isEmpty() || !columnOrderBy.isEmpty();
        boolean postPaging = needsSort || aggregateMode || stmt.distinct; // 去重/排序/聚合：先收集全部命中行，事后再处理
        // 读取上限：显式 LIMIT 且无需事后分页时直接用作收集上限
        int hardCap = !postPaging && stmt.limit != null
                ? stmt.limit
                : ModConfig.PERFORMANCE_MAX_SELECT_ROWS;

        // 流式逐行扫描：WHERE 在读取阶段求值，避免全量物化
        List<Map<String, Object>> collected = new ArrayList<>();
        long maxZ = tableManager.getMaxZ(stmt.tableName);
        int matchedCount = 0;
        boolean capReached = false;
        for (int z = 1; z < maxZ; z++) {
            Map<String, Object> row = tableManager.readRow(stmt.tableName, schema, z);
            if (row == null || row.isEmpty()) continue;

            if (stmt.whereClause != null) {
                List<Object> rowValues = new ArrayList<>();
                for (String col : columnNames) {
                    rowValues.add(row.get(col));
                }
                if (!stmt.whereClause.evaluate(columnNames, rowValues)) continue;
            }
            matchedCount++;

            if (!postPaging) {
                // 无排序无聚合：跳过 offset 个命中行，收满即提前结束扫描
                if (stmt.offset != null && matchedCount <= stmt.offset) continue;
            }
            if (collected.size() >= hardCap) {
                capReached = true;
                break;
            }
            collected.add(row);
        }

        // ---- 按表列排序物化行（隐藏排序列），再投影 ----
        if (!columnOrderBy.isEmpty()) {
            collected.sort((a, b) -> {
                for (SelectStatement.OrderBy ob : columnOrderBy) {
                    int cmp = compareValues(a.get(ob.column()), b.get(ob.column()));
                    if (cmp != 0) return ob.ascending() ? cmp : -cmp;
                }
                return 0;
            });
        }

        String message = "Selected %d row(s)";
        List<String> outColumns;
        List<List<Object>> rows = new ArrayList<>();

        if (aggregateMode) {
            // ---- 校验 SELECT 项与 GROUP BY ----
            Set<String> groupByResolved = new LinkedHashSet<>();
            for (String g : stmt.groupBy) {
                ColumnDefinition cd = findColumn(schema, g);
                if (cd == null) {
                    return QueryResult.error("Unknown column '" + g + "' in GROUP BY");
                }
                groupByResolved.add(cd.name);
            }
            for (SelectStatement.SelectItem item : items) {
                if (item.function() == null) {
                    ColumnDefinition cd = findColumn(schema, item.column());
                    if (cd == null) {
                        return QueryResult.error("Unknown column '" + item.column() + "' in SELECT");
                    }
                    if (!groupByResolved.contains(cd.name)) {
                        return QueryResult.error("Column '" + item.column()
                                + "' must appear in GROUP BY or be used in an aggregate");
                    }
                } else if (!"*".equals(item.column())
                        && findColumn(schema, item.column()) == null) {
                    return QueryResult.error("Unknown column '" + item.column()
                            + "' in " + item.function());
                }
            }

            // ---- 分组（无 GROUP BY 时整表一个组） ----
            Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            if (groupByResolved.isEmpty()) {
                groups.put("", collected);
            } else {
                for (Map<String, Object> row : collected) {
                    StringBuilder key = new StringBuilder();
                    for (String g : groupByResolved) {
                        key.append(String.valueOf(row.get(g))).append('\u0000');
                    }
                    groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(row);
                }
            }

            outColumns = new ArrayList<>();
            for (SelectStatement.SelectItem item : items) {
                outColumns.add(item.label());
            }
            for (List<Map<String, Object>> groupRows : groups.values()) {
                List<Object> out = new ArrayList<>();
                for (SelectStatement.SelectItem item : items) {
                    if (item.function() == null) {
                        out.add(groupRows.isEmpty() ? null
                                : groupRows.get(0).get(resolveSchemaName(schema, item.column())));
                    } else {
                        out.add(computeAggregate(item.function(), item.column(), schema, groupRows));
                    }
                }
                rows.add(out);
            }
        } else {
            outColumns = stmt.isSelectAll() ? columnNames : stmt.columns;
            for (Map<String, Object> row : collected) {
                List<Object> projected = new ArrayList<>();
                for (String col : outColumns) {
                    projected.add(row.getOrDefault(col, null));
                }
                rows.add(projected);
            }
        }

        // ---- DISTINCT：必须在 LIMIT/OFFSET 切片之前去重（SQL 语义） ----
        if (stmt.distinct) {
            LinkedHashMap<String, List<Object>> unique = new LinkedHashMap<>();
            for (List<Object> r : rows) {
                unique.putIfAbsent(r.toString(), r);
            }
            rows = new ArrayList<>(unique.values());
        }

        // ---- 按输出列排序（别名/聚合；Collections.sort 稳定，可与表列排序叠加） ----
        if (!labelOrderBy.isEmpty()) {
            int[] idxs = new int[labelOrderBy.size()];
            boolean[] ascs = new boolean[labelOrderBy.size()];
            for (int i = 0; i < labelOrderBy.size(); i++) {
                SelectStatement.OrderBy ob = labelOrderBy.get(i);
                idxs[i] = outColumns.indexOf(ob.column());
                ascs[i] = ob.ascending();
            }
            rows.sort((r1, r2) -> {
                for (int i = 0; i < idxs.length; i++) {
                    int idx = idxs[i];
                    Object a = idx >= 0 && idx < r1.size() ? r1.get(idx) : null;
                    Object b = idx >= 0 && idx < r2.size() ? r2.get(idx) : null;
                    int cmp = compareValues(a, b);
                    if (cmp != 0) return ascs[i] ? cmp : -cmp;
                }
                return 0;
            });
        }

        // ---- 事后分页（LIMIT/OFFSET） ----
        if (postPaging) {
            int from = stmt.offset != null ? Math.min(stmt.offset, rows.size()) : 0;
            int to = stmt.limit != null ? Math.min(from + stmt.limit, rows.size()) : rows.size();
            rows = new ArrayList<>(rows.subList(from, to));
        }

        if (capReached && stmt.limit == null) {
            message += "（达到 PERFORMANCE_MAX_SELECT_ROWS 上限，结果已截断）";
        }
        QueryResult result = QueryResult.ok(String.format(message, rows.size()));
        result.setColumns(outColumns);
        result.setRows(rows);
        result.setRowCount(rows.size());

        return result;
    }

    private String resolveSchemaName(List<ColumnDefinition> schema, String col) {
        ColumnDefinition cd = findColumn(schema, col);
        return cd != null ? cd.name : col;
    }

    /** 聚合计算：COUNT/SUM/AVG/MIN/MAX；SUM/AVG 只统计数值行，MIN/MAX 兼容字符串 */
    private Object computeAggregate(String fn, String col, List<ColumnDefinition> schema,
                                    List<Map<String, Object>> rows) {
        if ("COUNT".equals(fn)) {
            if ("*".equals(col)) return rows.size();
            String name = resolveSchemaName(schema, col);
            long n = 0;
            for (Map<String, Object> row : rows) {
                if (row.get(name) != null) n++;
            }
            return n;
        }
        String name = resolveSchemaName(schema, col);
        long numericCount = 0;
        double sum = 0;
        boolean allIntegral = true;
        Object min = null;
        Object max = null;
        for (Map<String, Object> row : rows) {
            Object v = row.get(name);
            if (v == null) continue;
            if (v instanceof Number n) {
                numericCount++;
                sum += n.doubleValue();
                if (!(v instanceof Integer || v instanceof Long)) allIntegral = false;
                if (min == null || n.doubleValue() < ((Number) min).doubleValue()) min = v;
                if (max == null || n.doubleValue() > ((Number) max).doubleValue()) max = v;
            } else {
                if (min == null || v.toString().compareTo(min.toString()) < 0) min = v;
                if (max == null || v.toString().compareTo(max.toString()) > 0) max = v;
            }
        }
        return switch (fn) {
            case "SUM" -> numericCount == 0 ? null : (allIntegral ? (Object) (long) sum : sum);
            case "AVG" -> numericCount == 0 ? null : sum / numericCount;
            case "MIN" -> min;
            case "MAX" -> max;
            default -> null;
        };
    }

    private QueryResult executeShowCreateTable(ShowCreateTableStatement stmt) {
        List<ColumnDefinition> schema = dbManager.getSchemaManager().getSchema(stmt.tableName);
        if (schema == null) {
            return QueryResult.error("Table '" + stmt.tableName + "' does not exist");
        }

        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(stmt.tableName).append(" (\n");
        for (int i = 0; i < schema.size(); i++) {
            ColumnDefinition cd = schema.get(i);
            sb.append("  `").append(cd.name).append("` ").append(cd.dataType);
            if (cd.autoIncrement) sb.append(" AUTO_INCREMENT");
            if (cd.isPrimaryKey()) {
                sb.append(" NOT NULL PRIMARY KEY");
            } else if (!cd.isNullable()) {
                sb.append(" NOT NULL");
            }
            if (cd.defaultValue != null) {
                sb.append(" DEFAULT ").append(cd.defaultValue);
            }
            sb.append(i < schema.size() - 1 ? "," : "").append("\n");
        }
        sb.append(");");

        QueryResult result = QueryResult.ok("1 row(s)");
        result.setColumns(List.of("Table", "Create Table"));
        result.setRows(List.of(List.of(stmt.tableName, sb.toString())));
        result.setRowCount(1);
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

        // SET 赋值只解析一次、只含被 SET 的列（此前每行重复解析且整行重写）
        Map<String, Object> parsedAssignments = new LinkedHashMap<>();
        for (Map.Entry<String, String> assign : stmt.assignments.entrySet()) {
            ColumnDefinition colDef = findColumn(schema, assign.getKey());
            if (colDef == null) {
                return QueryResult.error("Unknown column '" + assign.getKey() + "' in SET clause");
            }
            parsedAssignments.put(colDef.name, parseValue(assign.getValue(), colDef.dataType));
        }

        boolean autoCommit = !txnManager.isInTransaction();
        if (autoCommit) txnManager.begin();
        txnManager.acquireTableWriteLock(stmt.tableName);

        int updated = 0;
        try {
            long maxZ = tableManager.getMaxZ(stmt.tableName);
            for (int z = 1; z < maxZ; z++) {
                Map<String, Object> row = tableManager.readRow(stmt.tableName, schema, z);
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
                    // 校验被 SET 的列：主键/自增列唯一性与非空，其余列 NOT NULL
                    for (ColumnDefinition cd : schema) {
                        if (!parsedAssignments.containsKey(cd.name)) continue;
                        Object v = parsedAssignments.get(cd.name);
                        if (v == null) {
                            if (cd.isPrimaryKey() || cd.autoIncrement) {
                                throw new IllegalStateException("Column '" + cd.name
                                        + "' cannot be set to NULL ("
                                        + (cd.isPrimaryKey() ? "PRIMARY KEY" : "AUTO_INCREMENT") + ")");
                            }
                            if (!cd.isNullable()) {
                                throw new IllegalStateException("Column '" + cd.name
                                        + "' cannot be set to NULL (NOT NULL constraint)");
                            }
                            continue;
                        }
                        if (cd.isPrimaryKey() || cd.autoIncrement) {
                            Long dup = findDuplicateZ(stmt.tableName, schema, cd, v, z);
                            if (dup != null) {
                                throw new IllegalStateException("Duplicate value '" + v
                                        + "' for " + (cd.isPrimaryKey() ? "PRIMARY KEY" : "AUTO_INCREMENT")
                                        + " column '" + cd.name + "' (existing row at z=" + dup + ")");
                            }
                        }
                    }
                    tableManager.updateRow(stmt.tableName, schema, z, parsedAssignments);
                    updated++;
                }
            }
            tableManager.getBlockOperator().flush();

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

        boolean autoCommit = !txnManager.isInTransaction();
        if (autoCommit) txnManager.begin();
        txnManager.acquireTableWriteLock(stmt.tableName);

        int deleted = 0;
        try {
            // 流式逐行判断并删除，全部完成后合并为一次批量 flush
            long maxZ = tableManager.getMaxZ(stmt.tableName);
            for (int z = 1; z < maxZ; z++) {
                Map<String, Object> row = tableManager.readRow(stmt.tableName, schema, z);
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
                    tableManager.deleteRow(stmt.tableName, schema, z);
                    deleted++;
                }
            }
            tableManager.getBlockOperator().flush();

            if (autoCommit) txnManager.commit();
        } catch (Exception e) {
            if (autoCommit) txnManager.rollback();
            tableManager.getBlockOperator().clearPending();
            return QueryResult.error("Delete failed: " + e.getMessage());
        }

        return QueryResult.ok(deleted + " row(s) deleted from '" + stmt.tableName + "'");
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

        List<String> cols = List.of("Field", "Type", "Null", "Key", "Default", "Extra");
        List<List<Object>> rows = new ArrayList<>();
        for (ColumnDefinition cd : schema) {
            rows.add(List.of(
                    cd.name,
                    cd.dataType,
                    (cd.isNullable() && !cd.isPrimaryKey()) ? "YES" : "NO",
                    cd.isPrimaryKey() ? "PRI" : "",
                    cd.defaultValue != null ? stripQuotes(cd.defaultValue) : "NULL",
                    cd.autoIncrement ? "auto_increment" : ""
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
        return QueryResult.ok("Index '" + stmt.indexName + "' created on '"
                + stmt.tableName + "(" + stmt.columnName + ")'（当前索引为元数据，不参与查询计划）");
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
                try { yield Integer.parseInt(raw); }
                catch (NumberFormatException e) {
                    throw new SqlParser.ParseException("Invalid " + typeBase + " value: '" + raw + "'");
                }
            }
            case "BIGINT" -> {
                try { yield Long.parseLong(raw); }
                catch (NumberFormatException e) {
                    throw new SqlParser.ParseException("Invalid " + typeBase + " value: '" + raw + "'");
                }
            }
            case "FLOAT" -> {
                try { yield Float.parseFloat(raw); }
                catch (NumberFormatException e) {
                    throw new SqlParser.ParseException("Invalid " + typeBase + " value: '" + raw + "'");
                }
            }
            case "DOUBLE" -> {
                try { yield Double.parseDouble(raw); }
                catch (NumberFormatException e) {
                    throw new SqlParser.ParseException("Invalid " + typeBase + " value: '" + raw + "'");
                }
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
