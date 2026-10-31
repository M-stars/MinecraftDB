package com.minecraftdb.sql;

import com.minecraftdb.config.ModConfig;
import com.minecraftdb.database.DatabaseManager;
import com.minecraftdb.database.IndexManager;
import com.minecraftdb.database.SchemaManager;
import com.minecraftdb.database.TableManager;
import com.minecraftdb.database.TransactionManager;
import com.minecraftdb.sql.ast.*;

import java.util.*;
import java.util.stream.LongStream;

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
            case TRUNCATE_TABLE -> executeTruncate((TruncateStatement) stmt);
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
            case EXPLAIN -> executeExplain((ExplainStatement) stmt);
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

        // 外键目标校验：父表存在且引用列存在（自引用除外）
        for (ColumnDefinition cd : stmt.columns) {
            if (!cd.isForeignKey()) continue;
            if (cd.referencesTable.equalsIgnoreCase(stmt.tableName)) continue;
            if (!tableManager.tableExists(cd.referencesTable)) {
                return QueryResult.error("FOREIGN KEY target table '" + cd.referencesTable + "' does not exist");
            }
            List<ColumnDefinition> parentSchema = schemaManager.getSchema(cd.referencesTable);
            String parentCol = resolveReferencesColumn(cd, parentSchema);
            if (parentCol == null) {
                return QueryResult.error("FOREIGN KEY column '" + cd.name + "' references '"
                        + cd.referencesTable + "' which has no (single-column) primary key");
            }
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
            IndexManager idx = dbManager.getIndexManager();
            if (idx != null) idx.dropTable(stmt.tableName);
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
            IndexManager idx = dbManager.getIndexManager();
            if (idx != null) idx.invalidateTable(stmt.tableName); // 列集合可能变化，索引失效待重建
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

    /**
     * TRUNCATE [TABLE]：清空全部行并重置自增（nextZ 归 1）。
     * DDL 语义（隐式提交、不可回滚），事务内执行直接报错。
     */
    private QueryResult executeTruncate(TruncateStatement stmt) {
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
        if (txnManager.isInTransaction()) {
            return QueryResult.error("TRUNCATE is a DDL statement (implicit commit) and cannot run inside a transaction");
        }

        txnManager.acquireTableWriteLock(stmt.tableName);
        try {
            tableManager.truncateTable(stmt.tableName, schema);
            IndexManager idx = dbManager.getIndexManager();
            if (idx != null) idx.invalidateTable(stmt.tableName);
        } finally {
            txnManager.releaseTableWriteLock(stmt.tableName);
        }
        return QueryResult.ok("Table '" + stmt.tableName + "' truncated");
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
            List<ColumnDefinition> pkCols = columnsWithConstraint(schema, "PRIMARY KEY");
            boolean compositePk = pkCols.size() > 1;
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
                    // 单列唯一性：主键（复合主键改走元组检查）/自增/UNIQUE
                    if (parsedValue != null && !(compositePk && colDef.isPrimaryKey())
                            && (colDef.isPrimaryKey() || colDef.autoIncrement || colDef.isUnique())) {
                        String kind = colDef.isPrimaryKey() ? "PRIMARY KEY"
                                : colDef.autoIncrement ? "AUTO_INCREMENT" : "UNIQUE";
                        Long dup = findDuplicateZ(stmt.tableName, schema, colDef, parsedValue, -1);
                        if (dup != null) {
                            throw new IllegalStateException("Duplicate value '" + parsedValue
                                    + "' for " + kind + " column '" + colDef.name
                                    + "' (existing row at z=" + dup + ")");
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

                // 复合主键：元组唯一性（全行扫描比对）
                if (compositePk) {
                    checkCompositeUnique(stmt.tableName, schema, pkCols, rowData, -1);
                }

                // 外键：引用值必须存在于父表
                checkForeignKeysOnWrite(stmt.tableName, schema, rowData);

                long z = tableManager.insertRow(stmt.tableName, schema, rowData);
                recordIndexOp(stmt.tableName, () -> {
                    IndexManager idx = dbManager.getIndexManager();
                    if (idx != null) idx.onInsert(stmt.tableName, z, rowData);
                });
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

    /** 在表中查找与 value 相等的行（唯一性检查）；excludeZ 用于 UPDATE 排除本行。
     *  列可索引时走内存索引（候选集是超集，命中后仍逐行复核），否则回退全列扫描。
     *  当前事务写过该表时绕过索引——未提交的重复值只有全扫描（经写缓冲读）能发现。 */
    private Long findDuplicateZ(String tableName, List<ColumnDefinition> schema, ColumnDefinition col,
                                Object value, long excludeZ) {
        TableManager tm = dbManager.getTableManager();
        IndexManager idx = dbManager.getIndexManager();
        boolean txnDirty = dbManager.getTransactionManager().currentTxnWroteTable(tableName);
        if (idx != null && !txnDirty && idx.isIndexable(tableName, col.name)) {
            for (long z : idx.lookup(tableName, col.name, value)) {
                if (z == excludeZ) continue;
                Map<String, Object> row = tm.readRow(tableName, schema, (int) z);
                if (row == null || row.isEmpty()) continue;
                if (valueEquals(value, row.get(col.name))) {
                    return z;
                }
            }
            return null;
        }
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

        List<SelectStatement.SelectItem> items = stmt.selectItems;
        if (items == null) { // 旧构造器：从 columns 推导
            items = new ArrayList<>();
            for (String c : stmt.columns) {
                items.add(new SelectStatement.SelectItem(new Expression.ColumnRef(c), c));
            }
        }

        // 先解出 WHERE 中的子查询（物化为值列表/字面量），再进入计划与求值
        stmt = withResolvedWhere(stmt, resolveSubqueries(stmt.whereClause));

        // WHERE 中不允许聚合（标准 SQL 语义）
        if (stmt.whereClause != null) {
            Set<String> whereAggs = new LinkedHashSet<>();
            stmt.whereClause.collectAggregateNames(whereAggs);
            if (!whereAggs.isEmpty()) {
                return QueryResult.error("Aggregate function " + whereAggs.iterator().next()
                        + " is not allowed in WHERE (use HAVING)");
            }
        }

        // 收集参与查询的全部表上下文（主表在最前，JOIN 链依序）
        List<TableCtx> tables = new ArrayList<>();
        tables.add(new TableCtx(stmt.mainTable, schema));
        for (SelectStatement.JoinSpec join : stmt.joins) {
            if (!tableManager.tableExists(join.table().tableName())) {
                return QueryResult.error("Table '" + join.table().tableName() + "' does not exist");
            }
            List<ColumnDefinition> s = schemaManager.getSchema(join.table().tableName());
            if (s == null) {
                return QueryResult.error("Schema not found for table '" + join.table().tableName() + "'");
            }
            tables.add(new TableCtx(join.table(), s));
        }

        List<String> columnNames;
        List<List<Object>> rows;
        boolean capReached = false;

        if (stmt.isJoinQuery()) {
            JoinOutcome outcome = executeJoinPipeline(stmt, tables);
            if (outcome.error() != null) return QueryResult.error(outcome.error());
            columnNames = outcome.columnNames();
            rows = outcome.rows();
            capReached = outcome.capReached();
        } else {
            columnNames = new ArrayList<>();
            for (ColumnDefinition cd : schema) columnNames.add(cd.name);

            boolean aggregateMode = !stmt.groupBy.isEmpty() || stmt.havingClause != null
                    || items.stream().anyMatch(i -> i.function() != null);
            boolean postPaging = !stmt.orderBy.isEmpty() || aggregateMode || stmt.distinct;
            // 无需事后处理时提前终止扫描：收集 offset+limit 个命中行即够
            int hardCap = !postPaging && stmt.limit != null
                    ? stmt.limit + (stmt.offset != null ? stmt.offset : 0)
                    : ModConfig.PERFORMANCE_MAX_SELECT_ROWS;

            // 流式逐行扫描：WHERE 在读取阶段求值；索引等值条件走内存索引
            QueryPlanner.Plan plan = pickPlan(stmt.tableName, schema, stmt.whereClause);
            rows = new ArrayList<>();
            for (long zLong : rowSource(stmt.tableName, plan)) {
                int z = (int) zLong;
                Map<String, Object> row = tableManager.readRow(stmt.tableName, schema, z);
                if (row == null || row.isEmpty()) continue;

                if (stmt.whereClause != null) {
                    List<Object> rowValues = new ArrayList<>();
                    for (String col : columnNames) {
                        rowValues.add(row.get(col));
                    }
                    if (!stmt.whereClause.evaluate(columnNames, rowValues)) continue;
                }
                if (rows.size() >= hardCap) {
                    capReached = true;
                    break;
                }
                List<Object> aligned = new ArrayList<>(columnNames.size());
                for (String col : columnNames) aligned.add(row.get(col));
                rows.add(aligned);
            }
        }

        return finishSelect(stmt, items, columnNames, rows, capReached);
    }

    /** 参与查询的一张表：引用（含别名）+ schema */
    private record TableCtx(SelectStatement.TableRef ref, List<ColumnDefinition> schema) {}

    private record JoinOutcome(List<String> columnNames, List<List<Object>> rows,
                               boolean capReached, String error) {}

    /** 返回 WHERE 被替换为解出子查询版本的语句（其余字段不变） */
    private static SelectStatement withResolvedWhere(SelectStatement stmt, WhereClause where) {
        if (where == stmt.whereClause) return stmt;
        return new SelectStatement(stmt.columns, stmt.tableName, stmt.mainTable, stmt.joins, where,
                stmt.selectItems, stmt.groupBy, stmt.havingClause, stmt.distinct,
                stmt.orderBy, stmt.limit, stmt.offset);
    }

    /**
     * JOIN 执行管线：主表全量扫描 → 逐个 JOIN（嵌套循环；等值键探测右表内存索引，
     * LEFT 无匹配补 null 行）→ WHERE 过滤。WHERE 在 JOIN 后统一求值（标准语义），
     * 组合行数受 PERFORMANCE_MAX_SELECT_ROWS 双重保护（JOIN 产物 + 过滤结果）。
     */
    private JoinOutcome executeJoinPipeline(SelectStatement stmt, List<TableCtx> tables) {
        List<JoinNamespace.TableContext> nsTables = new ArrayList<>();
        for (TableCtx t : tables) {
            List<String> cols = new ArrayList<>();
            for (ColumnDefinition cd : t.schema()) cols.add(cd.name);
            nsTables.add(new JoinNamespace.TableContext(t.ref().qualifier(), cols));
        }
        JoinNamespace ns = JoinNamespace.build(nsTables);
        int qualifiedCount = ns.qualifiedNames.size();

        TableManager tm = dbManager.getTableManager();
        IndexManager idxMgr = dbManager.getIndexManager();
        int cap = ModConfig.PERFORMANCE_MAX_SELECT_ROWS;
        boolean capReached = false;

        // 主表全量扫描
        TableCtx main = tables.get(0);
        List<List<Object>> combined = new ArrayList<>();
        long maxZ = tm.getMaxZ(main.ref().tableName());
        for (int z = 1; z < maxZ && !capReached; z++) {
            Map<String, Object> row = tm.readRow(main.ref().tableName(), main.schema(), z);
            if (row == null || row.isEmpty()) continue;
            List<Object> c = new ArrayList<>(Collections.nCopies(qualifiedCount, null));
            fillTableSlots(c, ns.tableSlotOffsets.get(0), main.schema(), row);
            combined.add(c);
            if (combined.size() >= cap && tables.size() > 1) capReached = true;
        }

        // 逐个 JOIN
        for (int j = 1; j < tables.size() && !capReached; j++) {
            TableCtx right = tables.get(j);
            SelectStatement.JoinSpec spec = stmt.joins.get(j - 1);
            boolean leftJoin = "LEFT".equals(spec.type());
            int rightOffset = ns.tableSlotOffsets.get(j);

            JoinNamespace.EquiKey equi = ns.findEqui(spec.on(), j, j);
            boolean probeIndex = equi != null && idxMgr != null
                    && idxMgr.isIndexable(right.ref().tableName(), equi.rightColumn());

            // 无索引探测路径：右表全量物化一次
            List<List<Object>> rightRows = null;
            if (!probeIndex) {
                rightRows = new ArrayList<>();
                long rMaxZ = tm.getMaxZ(right.ref().tableName());
                for (int z = 1; z < rMaxZ; z++) {
                    Map<String, Object> row = tm.readRow(right.ref().tableName(), right.schema(), z);
                    if (row == null || row.isEmpty()) continue;
                    List<Object> vals = new ArrayList<>(right.schema().size());
                    for (ColumnDefinition cd : right.schema()) vals.add(row.get(cd.name));
                    rightRows.add(vals);
                }
            }

            List<List<Object>> next = new ArrayList<>();
            for (List<Object> left : combined) {
                boolean matched = false;
                if (probeIndex) {
                    Object leftVal = left.get(ns.qualifiedSlot(equi.leftName()));
                    if (leftVal != null) {
                        for (long z : idxMgr.lookup(right.ref().tableName(), equi.rightColumn(), leftVal)) {
                            Map<String, Object> row = tm.readRow(right.ref().tableName(), right.schema(), (int) z);
                            if (row == null || row.isEmpty()) continue;
                            List<Object> candidate = copyCombined(left, qualifiedCount);
                            fillTableSlots(candidate, rightOffset, right.schema(), row);
                            if (spec.on().evaluate(ns.resolverFor(candidate))) {
                                next.add(candidate);
                                matched = true;
                            }
                        }
                    }
                } else {
                    for (List<Object> rvals : rightRows) {
                        List<Object> candidate = copyCombined(left, qualifiedCount);
                        for (int i = 0; i < rvals.size(); i++) candidate.set(rightOffset + i, rvals.get(i));
                        if (spec.on().evaluate(ns.resolverFor(candidate))) {
                            next.add(candidate);
                            matched = true;
                        }
                    }
                }
                if (leftJoin && !matched) {
                    next.add(copyCombined(left, qualifiedCount)); // 右表槽位保持 null
                }
                if (next.size() >= cap) {
                    capReached = true;
                    break;
                }
            }
            combined = next;
        }

        // WHERE 过滤（组合行命名空间，含裸列与限定名）
        List<List<Object>> filtered = new ArrayList<>();
        for (List<Object> row : combined) {
            if (stmt.whereClause != null && !stmt.whereClause.evaluate(ns.resolverFor(row))) continue;
            if (filtered.size() >= cap) {
                capReached = true;
                break;
            }
            filtered.add(row);
        }
        return new JoinOutcome(ns.columnNames, filtered, capReached, null);
    }

    private List<Object> copyCombined(List<Object> left, int qualifiedCount) {
        List<Object> c = new ArrayList<>(left);
        while (c.size() < qualifiedCount) c.add(null);
        return c;
    }

    private void fillTableSlots(List<Object> c, int offset, List<ColumnDefinition> schema,
                                Map<String, Object> row) {
        int i = 0;
        for (ColumnDefinition cd : schema) {
            c.set(offset + i, row.get(cd.name));
            i++;
        }
    }

    /**
     * SELECT 统一尾部管线（单表与 JOIN 共用）：ORDER BY 分类 → 列排序 →
     * 聚合/GROUP BY/HAVING 或投影 → DISTINCT → 输出列排序 → 分页。
     * rows 与 columnNames 按槽位对齐。
     */
    private QueryResult finishSelect(SelectStatement stmt, List<SelectStatement.SelectItem> items,
                                     List<String> columnNames, List<List<Object>> rows,
                                     boolean capReached) {
        boolean aggregateMode = !stmt.groupBy.isEmpty() || stmt.havingClause != null
                || items.stream().anyMatch(i -> i.function() != null);

        // ORDER BY 分类：优先输出列（SELECT 别名/聚合表达式），否则表列（按未投影列排序）
        List<SelectStatement.OrderBy> labelOrderBy = new ArrayList<>();
        List<SelectStatement.OrderBy> columnOrderBy = new ArrayList<>();
        for (SelectStatement.OrderBy ob : stmt.orderBy) {
            String label = null;
            for (SelectStatement.SelectItem item : items) {
                if (item.label().equalsIgnoreCase(ob.column())
                        || (item.column() != null && item.column().equalsIgnoreCase(ob.column()))) {
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
                if (indexOfIgnoreCase(columnNames, ob.column()) < 0) {
                    return QueryResult.error("Unknown column '" + ob.column() + "' in ORDER BY");
                }
                columnOrderBy.add(ob);
            }
        }
        boolean needsSort = !labelOrderBy.isEmpty() || !columnOrderBy.isEmpty();
        boolean postPaging = needsSort || aggregateMode || stmt.distinct;

        // ---- 按表列排序（对齐行，隐藏排序列）----
        if (!columnOrderBy.isEmpty()) {
            rows.sort((a, b) -> {
                for (SelectStatement.OrderBy ob : columnOrderBy) {
                    int idx = indexOfIgnoreCase(columnNames, ob.column());
                    int cmp = compareValues(valAt(a, idx), valAt(b, idx));
                    if (cmp != 0) return ob.ascending() ? cmp : -cmp;
                }
                return 0;
            });
        }

        String message = "Selected %d row(s)";
        List<String> outColumns;
        List<List<Object>> outRows = new ArrayList<>();

        if (aggregateMode) {
            // ---- 解析 GROUP BY 列 ----
            Set<String> groupByResolved = new LinkedHashSet<>();
            for (String g : stmt.groupBy) {
                String resolved = resolveColumn(columnNames, g);
                if (resolved == null) {
                    return QueryResult.error("Unknown column '" + g + "' in GROUP BY");
                }
                groupByResolved.add(resolved);
            }

            // ---- 收集并校验聚合调用（SELECT 项 + HAVING）----
            Set<String> aggCanonicals = new LinkedHashSet<>();
            for (SelectStatement.SelectItem item : items) {
                Expression.collectAggregates(item.expr(), aggCanonicals);
            }
            if (stmt.havingClause != null) {
                stmt.havingClause.collectAggregateNames(aggCanonicals);
            }
            for (String canonical : aggCanonicals) {
                String[] fnArg = splitAggregateCanonical(canonical);
                if (!"*".equals(fnArg[1]) && resolveColumn(columnNames, fnArg[1]) == null) {
                    return QueryResult.error("Unknown column '" + fnArg[1] + "' in " + fnArg[0]);
                }
            }

            // ---- 校验 SELECT 项中的裸列必须出现在 GROUP BY ----
            Set<String> bareColumns = new LinkedHashSet<>();
            for (SelectStatement.SelectItem item : items) {
                Expression.collectBareColumns(item.expr(), bareColumns);
            }
            for (String c : bareColumns) {
                String resolved = resolveColumn(columnNames, c);
                if (resolved == null) {
                    return QueryResult.error("Unknown column '" + c + "' in SELECT");
                }
                if (!groupByResolved.contains(resolved)) {
                    return QueryResult.error("Column '" + c
                            + "' must appear in GROUP BY or be used in an aggregate");
                }
            }

            // ---- 分组（无 GROUP BY 时整表一个组） ----
            Map<String, List<List<Object>>> groups = new LinkedHashMap<>();
            if (groupByResolved.isEmpty()) {
                groups.put("", rows);
            } else {
                int[] gIdx = groupByResolved.stream().mapToInt(n -> indexOfIgnoreCase(columnNames, n)).toArray();
                for (List<Object> row : rows) {
                    StringBuilder key = new StringBuilder();
                    for (int idx : gIdx) {
                        key.append(String.valueOf(valAt(row, idx))).append('\u0000');
                    }
                    groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(row);
                }
            }

            outColumns = new ArrayList<>();
            for (SelectStatement.SelectItem item : items) {
                outColumns.add(item.label());
            }
            for (List<List<Object>> groupRows : groups.values()) {
                // 组内聚合预计算，键为规范化名（小写）
                Map<String, Object> aggValues = new HashMap<>();
                for (String canonical : aggCanonicals) {
                    String[] fnArg = splitAggregateCanonical(canonical);
                    aggValues.put(canonical.toLowerCase(Locale.ROOT),
                            computeAggregate(fnArg[0], fnArg[1], columnNames, groupRows));
                }
                List<Object> firstValues = groupRows.isEmpty() ? List.of() : groupRows.get(0);
                Expression.Resolver rowRes = WhereClause.resolverOf(columnNames, firstValues);
                Expression.Resolver resolver = name -> {
                    String key = name.toLowerCase(Locale.ROOT);
                    if (aggValues.containsKey(key)) return aggValues.get(key);
                    return rowRes.value(name);
                };

                List<Object> out = new ArrayList<>();
                for (SelectStatement.SelectItem item : items) {
                    out.add(item.expr().eval(resolver));
                }

                // HAVING 过滤：优先按输出列（别名），其次组内聚合，最后组首行
                if (stmt.havingClause != null) {
                    Expression.Resolver havingRes = new Expression.Resolver() {
                        @Override
                        public Object value(String name) {
                            int idx = indexOfIgnoreCase(outColumns, name);
                            if (idx >= 0) return out.get(idx);
                            String key = name.toLowerCase(Locale.ROOT);
                            if (aggValues.containsKey(key)) return aggValues.get(key);
                            return rowRes.value(name);
                        }

                        @Override
                        public boolean exists(String name) {
                            if (indexOfIgnoreCase(outColumns, name) >= 0) return true;
                            String key = name.toLowerCase(Locale.ROOT);
                            if (aggValues.containsKey(key)) return true;
                            return rowRes.exists(name);
                        }
                    };
                    if (!stmt.havingClause.evaluate(havingRes)) continue;
                }
                outRows.add(out);
            }
        } else {
            outColumns = stmt.isSelectAll() ? new ArrayList<>(columnNames) : stmt.columns;
            for (List<Object> row : rows) {
                if (stmt.isSelectAll()) {
                    outRows.add(new ArrayList<>(row));
                } else {
                    Expression.Resolver rowRes = WhereClause.resolverOf(columnNames, row);
                    List<Object> projected = new ArrayList<>();
                    for (SelectStatement.SelectItem item : items) {
                        projected.add(item.expr().eval(rowRes));
                    }
                    outRows.add(projected);
                }
            }
        }

        // ---- DISTINCT：必须在 LIMIT/OFFSET 切片之前去重（SQL 语义） ----
        if (stmt.distinct) {
            LinkedHashMap<String, List<Object>> unique = new LinkedHashMap<>();
            for (List<Object> r : outRows) {
                unique.putIfAbsent(r.toString(), r);
            }
            outRows = new ArrayList<>(unique.values());
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
            outRows.sort((r1, r2) -> {
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

        // ---- 分页（LIMIT/OFFSET，在去重/排序之后） ----
        if (postPaging || stmt.limit != null || stmt.offset != null) {
            int from = stmt.offset != null ? Math.min(stmt.offset, outRows.size()) : 0;
            int to = stmt.limit != null ? Math.min(from + stmt.limit, outRows.size()) : outRows.size();
            outRows = new ArrayList<>(outRows.subList(from, to));
        }

        if (capReached && stmt.limit == null) {
            message += "（达到 PERFORMANCE_MAX_SELECT_ROWS 上限，结果已截断）";
        }
        QueryResult result = QueryResult.ok(String.format(message, outRows.size()));
        result.setColumns(outColumns);
        result.setRows(outRows);
        result.setRowCount(outRows.size());

        return result;
    }

    // === 子查询解析（非相关子查询 → 执行 → 替换为字面量/值列表） ===

    /**
     * 解出 WHERE 中的子查询：IN 子查询物化为值列表，标量子查询求值为字面量。
     * 子查询经由完整 execute() 执行，与外层语句共享会话事务上下文。
     * 仅支持非相关子查询（引用外层列会解析为未知列）。
     */
    private WhereClause resolveSubqueries(WhereClause where) {
        if (where == null || !hasSubqueryNode(where.getRoot())) return where;
        return new WhereClause(rebuildNode(where.getRoot()));
    }

    private WhereClause.Node rebuildNode(WhereClause.Node node) {
        if (node instanceof WhereClause.InSubqueryCondition inq) {
            return new WhereClause.InCondition(inq.getOperand(),
                    executeSubqueryValues(inq.getSub()), inq.isNegated());
        }
        if (node instanceof WhereClause.OpCondition op) {
            return new WhereClause.OpCondition(op.getOperand(), op.getOperator(),
                    rebuildExpr(op.getValue()));
        }
        if (node instanceof WhereClause.AndNode a) {
            return new WhereClause.AndNode(rebuildNode(a.getLeft()), rebuildNode(a.getRight()));
        }
        if (node instanceof WhereClause.OrNode o) {
            return new WhereClause.OrNode(rebuildNode(o.getLeft()), rebuildNode(o.getRight()));
        }
        if (node instanceof WhereClause.NotNode nt) {
            return new WhereClause.NotNode(rebuildNode(nt.getInner()));
        }
        return node; // Between/Like/Null/In：不含子查询
    }

    private Expression rebuildExpr(Expression e) {
        if (e instanceof SubqueryExpression sq) {
            Object value = executeScalarSubquery(sq.sub);
            return new Expression.Literal(value, String.valueOf(value));
        }
        if (e instanceof Expression.Arith a) {
            return new Expression.Arith(a.op, rebuildExpr(a.left), rebuildExpr(a.right));
        }
        if (e instanceof Expression.Func f) {
            List<Expression> args = new ArrayList<>(f.args.size());
            for (Expression a : f.args) args.add(rebuildExpr(a));
            return new Expression.Func(f.name, args);
        }
        return e; // Literal / ColumnRef
    }

    private static boolean hasSubqueryNode(WhereClause.Node n) {
        if (n instanceof WhereClause.InSubqueryCondition) return true;
        if (n instanceof WhereClause.Leaf leaf) return hasSubqueryExpr(leaf.getOperand());
        if (n instanceof WhereClause.NotNode nt) return hasSubqueryNode(nt.getInner());
        if (n instanceof WhereClause.AndNode a) {
            return hasSubqueryNode(a.getLeft()) || hasSubqueryNode(a.getRight());
        }
        if (n instanceof WhereClause.OrNode o) {
            return hasSubqueryNode(o.getLeft()) || hasSubqueryNode(o.getRight());
        }
        return false;
    }

    private static boolean hasSubqueryExpr(Expression e) {
        if (e instanceof SubqueryExpression) return true;
        if (e instanceof Expression.Arith a) {
            return hasSubqueryExpr(a.left) || hasSubqueryExpr(a.right);
        }
        if (e instanceof Expression.Func f) {
            for (Expression a : f.args) {
                if (hasSubqueryExpr(a)) return true;
            }
        }
        return false;
    }

    /** IN 子查询 → 首列值列表（null 值剔除：IN 对 null 恒不匹配，与 SQL unknown 语义一致） */
    private List<String> executeSubqueryValues(SelectStatement sub) {
        QueryResult r = execute(sub);
        if (!r.isSuccess()) {
            throw new SqlParser.ParseException("Subquery failed: " + r.getMessage());
        }
        if (r.getColumns().size() != 1) {
            throw new SqlParser.ParseException("IN subquery must return exactly one column");
        }
        List<String> values = new ArrayList<>();
        for (List<Object> row : r.getRows()) {
            Object v = row.isEmpty() ? null : row.get(0);
            if (v == null) continue;
            values.add(v instanceof String s ? "'" + s + "'" : v.toString());
        }
        return values;
    }

    /** 标量子查询 → 单值（空结果为 null；多行/多列报错，与标准 SQL 一致） */
    private Object executeScalarSubquery(SelectStatement sub) {
        QueryResult r = execute(sub);
        if (!r.isSuccess()) {
            throw new SqlParser.ParseException("Subquery failed: " + r.getMessage());
        }
        if (r.getColumns().size() != 1) {
            throw new SqlParser.ParseException("Scalar subquery must return exactly one column");
        }
        if (r.getRows().size() > 1) {
            throw new SqlParser.ParseException("Scalar subquery returned more than 1 row");
        }
        if (r.getRows().isEmpty()) return null;
        List<Object> row = r.getRows().get(0);
        return row.isEmpty() ? null : row.get(0);
    }

    /** 记录索引维护操作：事务内延迟到提交点应用，无事务立即应用 */
    private void recordIndexOp(String table, Runnable op) {
        dbManager.getTransactionManager().recordIndexOp(table, op);
    }

    // === 约束执行（复合主键 / UNIQUE / 外键） ===

    private static List<ColumnDefinition> columnsWithConstraint(List<ColumnDefinition> schema, String constraint) {
        List<ColumnDefinition> out = new ArrayList<>();
        for (ColumnDefinition cd : schema) {
            if (cd.hasConstraint(constraint)) out.add(cd);
        }
        return out;
    }

    /** 复合主键元组唯一性：扫描全表比对组合值（excludeZ 用于 UPDATE 排除本行） */
    private void checkCompositeUnique(String tableName, List<ColumnDefinition> schema,
                                      List<ColumnDefinition> pkCols, Map<String, Object> rowData,
                                      long excludeZ) {
        TableManager tm = dbManager.getTableManager();
        long maxZ = tm.getMaxZ(tableName);
        for (int z = 1; z < maxZ; z++) {
            if (z == excludeZ) continue;
            Map<String, Object> row = tm.readRow(tableName, schema, z);
            if (row == null || row.isEmpty()) continue;
            boolean allEqual = true;
            for (ColumnDefinition cd : pkCols) {
                if (!valueEquals(rowData.get(cd.name), row.get(cd.name))) {
                    allEqual = false;
                    break;
                }
            }
            if (allEqual) {
                StringBuilder tuple = new StringBuilder("(");
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) tuple.append(", ");
                    tuple.append(rowData.get(pkCols.get(i).name));
                }
                tuple.append(")");
                throw new IllegalStateException("Duplicate PRIMARY KEY " + tuple
                        + " (existing row at z=" + z + ")");
            }
        }
    }

    /** 外键写入校验：引用列的非空值必须存在于父表（父表列可索引时走索引预筛） */
    private void checkForeignKeysOnWrite(String tableName, List<ColumnDefinition> schema,
                                         Map<String, Object> data) {
        SchemaManager schemaManager = dbManager.getSchemaManager();
        for (ColumnDefinition cd : schema) {
            if (!cd.isForeignKey() || !data.containsKey(cd.name)) continue;
            Object v = data.get(cd.name);
            if (v == null) continue; // SQL 默认 MATCH SIMPLE：NULL 引用不校验

            String parentTable = cd.referencesTable;
            List<ColumnDefinition> parentSchema = schemaManager.getSchema(parentTable);
            if (parentSchema == null) {
                throw new IllegalStateException("FOREIGN KEY target table '" + parentTable + "' does not exist");
            }
            String parentCol = resolveReferencesColumn(cd, parentSchema);
            if (!parentHasValue(parentTable, parentSchema, parentCol, v)) {
                throw new IllegalStateException("FOREIGN KEY constraint fails on '" + tableName + "." + cd.name
                        + "': value " + formatValue(v) + " not found in " + parentTable + "(" + parentCol + ")");
            }
        }
    }

    /** 父表是否存在引用值（走行读取 + 逐值比对；父列可索引时先经索引缩小候选集） */
    private boolean parentHasValue(String parentTable, List<ColumnDefinition> parentSchema,
                                   String parentCol, Object value) {
        TableManager tm = dbManager.getTableManager();
        ColumnDefinition parentCd = findColumn(parentSchema, parentCol);
        if (parentCd == null) return false;
        IndexManager idx = dbManager.getIndexManager();
        boolean txnDirty = dbManager.getTransactionManager().currentTxnWroteTable(parentTable);
        if (idx != null && !txnDirty && idx.isIndexable(parentTable, parentCol)) {
            for (long z : idx.lookup(parentTable, parentCol, value)) {
                Map<String, Object> row = tm.readRow(parentTable, parentSchema, (int) z);
                if (row != null && !row.isEmpty() && valueEquals(value, row.get(parentCd.name))) {
                    return true;
                }
            }
            return false;
        }
        long maxZ = tm.getMaxZ(parentTable);
        for (int z = 1; z < maxZ; z++) {
            Map<String, Object> row = tm.readRow(parentTable, parentSchema, z);
            if (row == null || row.isEmpty()) continue;
            if (valueEquals(value, row.get(parentCd.name))) return true;
        }
        return false;
    }

    /** 引用列解析：显式列名，或父表的单列主键 */
    private String resolveReferencesColumn(ColumnDefinition cd, List<ColumnDefinition> parentSchema) {
        if (cd.referencesColumn != null) return cd.referencesColumn;
        if (parentSchema == null) return null;
        List<ColumnDefinition> pk = columnsWithConstraint(parentSchema, "PRIMARY KEY");
        return pk.size() == 1 ? pk.get(0).name : null;
    }

    /** 找出引用 (table, column) 的全部子表外键列 */
    private List<Object[]> findChildReferences(String tableName, String columnName) {
        List<Object[]> out = new ArrayList<>(); // {childTableName, childColumnDefinition}
        SchemaManager schemaManager = dbManager.getSchemaManager();
        for (String t : dbManager.getTableManager().listTables()) {
            List<ColumnDefinition> s = schemaManager.getSchema(t);
            if (s == null) continue;
            for (ColumnDefinition cd : s) {
                if (!cd.isForeignKey()) continue;
                if (!cd.referencesTable.equalsIgnoreCase(tableName)) continue;
                // 该子外键解析到的父列与被删/被改列一致（父列显式指定或默认父表主键）
                List<ColumnDefinition> parentSchema = schemaManager.getSchema(tableName);
                String resolvedParent = resolveReferencesColumn(cd, parentSchema);
                String target = columnName;
                boolean matches = resolvedParent != null
                        ? resolvedParent.equalsIgnoreCase(target)
                        : isSinglePrimaryKey(parentSchema, target);
                if (matches) {
                    out.add(new Object[]{t, cd});
                }
            }
        }
        return out;
    }

    private static boolean isSinglePrimaryKey(List<ColumnDefinition> schema, String columnName) {
        List<ColumnDefinition> pk = columnsWithConstraint(schema, "PRIMARY KEY");
        return pk.size() == 1 && pk.get(0).name.equalsIgnoreCase(columnName);
    }

    /** DELETE 父端限制：删除行中存在被子表引用的值 → 拒绝（RESTRICT） */
    private void checkParentSideDelete(String tableName, Map<String, Object> row) {
        List<ColumnDefinition> schema = dbManager.getSchemaManager().getSchema(tableName);
        if (schema == null) return;
        for (ColumnDefinition cd : schema) {
            List<Object[]> children = findChildReferences(tableName, cd.name);
            if (children.isEmpty()) continue;
            Object v = row.get(cd.name);
            if (v == null) continue;
            for (Object[] child : children) {
                String childTable = (String) child[0];
                ColumnDefinition childCd = (ColumnDefinition) child[1];
                if (childHasReference(childTable, childCd, v)) {
                    throw new IllegalStateException("Cannot delete row from '" + tableName
                            + "': value " + formatValue(v) + " of '" + cd.name
                            + "' is still referenced by " + childTable + "(" + childCd.name + ")");
                }
            }
        }
    }

    /** UPDATE 父端限制：被引用列的值变更时，旧值必须无子表行引用（RESTRICT） */
    private void checkParentSideUpdate(String tableName, Map<String, Object> oldRow,
                                       Map<String, Object> newValues) {
        List<ColumnDefinition> schema = dbManager.getSchemaManager().getSchema(tableName);
        if (schema == null) return;
        for (ColumnDefinition cd : schema) {
            if (!newValues.containsKey(cd.name)) continue;
            Object oldV = oldRow.get(cd.name);
            Object newV = newValues.get(cd.name);
            if (oldV == null || valueEquals(oldV, newV)) continue;
            List<Object[]> children = findChildReferences(tableName, cd.name);
            for (Object[] child : children) {
                String childTable = (String) child[0];
                ColumnDefinition childCd = (ColumnDefinition) child[1];
                if (childHasReference(childTable, childCd, oldV)) {
                    throw new IllegalStateException("Cannot update '" + tableName + "." + cd.name
                            + "': old value " + formatValue(oldV)
                            + " is still referenced by " + childTable + "(" + childCd.name + ")");
                }
            }
        }
    }

    /** 子表是否存在引用该值的行 */
    private boolean childHasReference(String childTable, ColumnDefinition childCd, Object value) {
        TableManager tm = dbManager.getTableManager();
        List<ColumnDefinition> childSchema = dbManager.getSchemaManager().getSchema(childTable);
        if (childSchema == null) return false;
        long maxZ = tm.getMaxZ(childTable);
        for (int z = 1; z < maxZ; z++) {
            Map<String, Object> row = tm.readRow(childTable, childSchema, z);
            if (row == null || row.isEmpty()) continue;
            if (valueEquals(value, row.get(childCd.name))) return true;
        }
        return false;
    }

    private static String formatValue(Object v) {
        return v instanceof String s ? "'" + s + "'" : String.valueOf(v);
    }

    // === 查询计划 ===

    /** 为语句挑查询计划：索引等值查找或全表扫描（IndexManager 不可用时一律全扫描）。
     *  当前事务写过该表时绕过索引走全扫描——索引是提交态，必须全扫才能读到自己的未提交写入。 */
    private QueryPlanner.Plan pickPlan(String tableName, List<ColumnDefinition> schema, WhereClause where) {
        IndexManager idx = dbManager.getIndexManager();
        if (idx == null) return new QueryPlanner.Plan(null, null, false);
        if (dbManager.getTransactionManager().currentTxnWroteTable(tableName)) {
            return new QueryPlanner.Plan(null, null, false);
        }
        return QueryPlanner.pickPlan(idx.indexableColumns(tableName), schema, where);
    }

    /** 计划对应的候选行号源：索引命中集合或 z=1..maxZ-1 全表 */
    private Iterable<Long> rowSource(String tableName, QueryPlanner.Plan plan) {
        if (plan.indexUsed()) {
            return dbManager.getIndexManager().lookup(tableName, plan.column(), plan.key());
        }
        long maxZ = dbManager.getTableManager().getMaxZ(tableName);
        return () -> LongStream.range(1, maxZ).iterator();
    }

    /** 列名解析（大小写不敏感）→ 命名空间中的规范显示名；不存在返回 null */
    private static String resolveColumn(List<String> columnNames, String name) {
        int idx = indexOfIgnoreCase(columnNames, name);
        return idx >= 0 ? columnNames.get(idx) : null;
    }

    private static Object valAt(List<Object> row, int idx) {
        return idx >= 0 && idx < row.size() ? row.get(idx) : null;
    }

    /** 聚合规范化名拆分：COUNT(*) → [COUNT, *]，SUM(price) → [SUM, price] */
    private static String[] splitAggregateCanonical(String canonical) {
        int lp = canonical.indexOf('(');
        int rp = canonical.lastIndexOf(')');
        if (lp <= 0 || rp != canonical.length() - 1) {
            return new String[]{canonical, canonical};
        }
        return new String[]{canonical.substring(0, lp), canonical.substring(lp + 1, rp)};
    }

    private static int indexOfIgnoreCase(List<String> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /** 聚合计算：COUNT/SUM/AVG/MIN/MAX；SUM/AVG 只统计数值行，MIN/MAX 兼容字符串 */
    private Object computeAggregate(String fn, String col, List<String> columnNames,
                                    List<List<Object>> rows) {
        if ("COUNT".equals(fn)) {
            if ("*".equals(col)) return (long) rows.size();
            int idx = indexOfIgnoreCase(columnNames, col);
            long n = 0;
            for (List<Object> row : rows) {
                if (valAt(row, idx) != null) n++;
            }
            return n;
        }
        int idx = indexOfIgnoreCase(columnNames, col);
        long numericCount = 0;
        double sum = 0;
        boolean allIntegral = true;
        Object min = null;
        Object max = null;
        for (List<Object> row : rows) {
            Object v = valAt(row, idx);
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
        List<ColumnDefinition> pkCols = columnsWithConstraint(schema, "PRIMARY KEY");
        boolean compositePk = pkCols.size() > 1;
        for (int i = 0; i < schema.size(); i++) {
            ColumnDefinition cd = schema.get(i);
            sb.append("  `").append(cd.name).append("` ").append(cd.dataType);
            if (cd.autoIncrement) sb.append(" AUTO_INCREMENT");
            if (cd.isPrimaryKey() && !compositePk) {
                sb.append(" NOT NULL PRIMARY KEY");
            } else if (!cd.isNullable()) {
                sb.append(" NOT NULL");
            }
            if (cd.isUnique()) sb.append(" UNIQUE");
            if (cd.isForeignKey()) {
                sb.append(" REFERENCES ").append(cd.referencesTable);
                if (cd.referencesColumn != null) sb.append("(").append(cd.referencesColumn).append(")");
            }
            if (cd.defaultValue != null) {
                sb.append(" DEFAULT ").append(cd.defaultValue);
            }
            sb.append(i < schema.size() - 1 ? "," : "").append("\n");
        }
        if (compositePk) {
            sb.append("  PRIMARY KEY (");
            for (int i = 0; i < pkCols.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("`").append(pkCols.get(i).name).append("`");
            }
            sb.append(")\n");
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

        // 解出 WHERE 中的子查询（物化为值列表/字面量）
        stmt = new UpdateStatement(stmt.tableName, stmt.assignments, resolveSubqueries(stmt.whereClause));

        // SET 赋值校验：列存在、不含聚合（表达式在每行求值，支持 col = col + 1）
        for (Map.Entry<String, Expression> assign : stmt.assignments.entrySet()) {
            ColumnDefinition colDef = findColumn(schema, assign.getKey());
            if (colDef == null) {
                return QueryResult.error("Unknown column '" + assign.getKey() + "' in SET clause");
            }
            Set<String> aggs = new LinkedHashSet<>();
            Expression.collectAggregates(assign.getValue(), aggs);
            if (!aggs.isEmpty()) {
                return QueryResult.error("Aggregate function " + aggs.iterator().next()
                        + " is not allowed in SET clause");
            }
        }

        boolean autoCommit = !txnManager.isInTransaction();
        if (autoCommit) txnManager.begin();
        txnManager.acquireTableWriteLock(stmt.tableName);

        int updated = 0;
        try {
            final String updateTable = stmt.tableName;
            List<ColumnDefinition> pkCols = columnsWithConstraint(schema, "PRIMARY KEY");
            boolean compositePk = pkCols.size() > 1;
            QueryPlanner.Plan plan = pickPlan(stmt.tableName, schema, stmt.whereClause);
            for (long zLong : rowSource(stmt.tableName, plan)) {
                int z = (int) zLong;
                Map<String, Object> row = tableManager.readRow(stmt.tableName, schema, z);
                if (row == null || row.isEmpty()) continue;

                boolean matches = true;
                List<Object> rowValues = new ArrayList<>();
                for (String col : columnNames) {
                    rowValues.add(row.get(col));
                }
                if (stmt.whereClause != null) {
                    matches = stmt.whereClause.evaluate(columnNames, rowValues);
                }

                if (matches) {
                    // 逐列求值 SET 表达式（基于本行原值），并校验主键/自增/UNIQUE 唯一性与非空
                    Expression.Resolver rowRes = WhereClause.resolverOf(columnNames, rowValues);
                    Map<String, Object> newValues = new LinkedHashMap<>();
                    for (Map.Entry<String, Expression> assign : stmt.assignments.entrySet()) {
                        ColumnDefinition cd = findColumn(schema, assign.getKey());
                        Object v = coerceValue(assign.getValue().eval(rowRes), cd.dataType);
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
                        } else if (!(compositePk && cd.isPrimaryKey())
                                && (cd.isPrimaryKey() || cd.autoIncrement || cd.isUnique())) {
                            String kind = cd.isPrimaryKey() ? "PRIMARY KEY"
                                    : cd.autoIncrement ? "AUTO_INCREMENT" : "UNIQUE";
                            Long dup = findDuplicateZ(stmt.tableName, schema, cd, v, z);
                            if (dup != null) {
                                throw new IllegalStateException("Duplicate value '" + v
                                        + "' for " + kind + " column '" + cd.name
                                        + "' (existing row at z=" + dup + ")");
                            }
                        }
                        newValues.put(cd.name, v);
                    }

                    // 复合主键：任一主键列被 SET → 元组唯一性（合并后整行比对）
                    if (compositePk) {
                        boolean pkTouched = pkCols.stream().anyMatch(cd -> newValues.containsKey(cd.name));
                        if (pkTouched) {
                            Map<String, Object> merged = new LinkedHashMap<>(row);
                            merged.putAll(newValues);
                            checkCompositeUnique(stmt.tableName, schema, pkCols, merged, z);
                        }
                    }

                    // 外键：SET 的引用列新值必须存在于父表
                    checkForeignKeysOnWrite(stmt.tableName, schema, newValues);

                    // 父端限制：被引用列的值变更前，须无子表行仍引用旧值（RESTRICT）
                    checkParentSideUpdate(stmt.tableName, row, newValues);
                    // 索引维护：以「更新后的完整行」重登记值发生变化的可索引列（提交点应用）
                    Map<String, Object> merged = new LinkedHashMap<>(row);
                    merged.putAll(newValues);
                    recordIndexOp(updateTable, () -> {
                        IndexManager idx = dbManager.getIndexManager();
                        if (idx != null) idx.onUpdate(updateTable, z, row, merged);
                    });
                    tableManager.updateRow(stmt.tableName, schema, z, newValues);
                    updated++;
                }
            }
            tableManager.getBlockOperator().flush();

            if (autoCommit) txnManager.commit();
        } catch (Exception e) {
            if (autoCommit) txnManager.rollback();
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

        // 解出 WHERE 中的子查询（物化为值列表/字面量）
        stmt = new DeleteStatement(stmt.tableName, resolveSubqueries(stmt.whereClause));

        boolean autoCommit = !txnManager.isInTransaction();
        if (autoCommit) txnManager.begin();
        txnManager.acquireTableWriteLock(stmt.tableName);

        int deleted = 0;
        try {
            final String deleteTable = stmt.tableName;
            // 流式逐行判断并删除，全部完成后合并为一次批量 flush
            QueryPlanner.Plan plan = pickPlan(stmt.tableName, schema, stmt.whereClause);
            for (long zLong : rowSource(stmt.tableName, plan)) {
                int z = (int) zLong;
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
                    // 父端限制：被引用列上有子表行引用该值 → 拒绝删除（RESTRICT）
                    checkParentSideDelete(stmt.tableName, row);
                    recordIndexOp(deleteTable, () -> {
                        IndexManager idx = dbManager.getIndexManager();
                        if (idx != null) idx.onDelete(deleteTable, z, row);
                    });
                    tableManager.deleteRow(stmt.tableName, schema, z);
                    deleted++;
                }
            }
            tableManager.getBlockOperator().flush();

            if (autoCommit) txnManager.commit();
        } catch (Exception e) {
            if (autoCommit) txnManager.rollback();
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

    /** EXPLAIN SELECT/UPDATE/DELETE：不执行语句，输出查询计划 */
    private QueryResult executeExplain(ExplainStatement stmt) {
        Statement inner = stmt.inner;
        if (inner instanceof SelectStatement sel) {
            return explainTableStatement(sel.tableName, sel.whereClause, "SELECT");
        }
        if (inner instanceof UpdateStatement upd) {
            return explainTableStatement(upd.tableName, upd.whereClause, "UPDATE");
        }
        if (inner instanceof DeleteStatement del) {
            return explainTableStatement(del.tableName, del.whereClause, "DELETE");
        }
        return QueryResult.error("EXPLAIN only supports SELECT, UPDATE or DELETE");
    }

    private QueryResult explainTableStatement(String tableName, WhereClause where, String kind) {
        TableManager tableManager = dbManager.getTableManager();
        SchemaManager schemaManager = dbManager.getSchemaManager();
        IndexManager idx = dbManager.getIndexManager();

        if (!tableManager.tableExists(tableName)) {
            return QueryResult.error("Table '" + tableName + "' does not exist");
        }
        List<ColumnDefinition> schema = schemaManager.getSchema(tableName);
        if (schema == null) {
            return QueryResult.error("Schema not found for table '" + tableName + "'");
        }

        Set<String> indexable = idx != null ? idx.indexableColumns(tableName) : Set.of();
        // 与实际执行一致：当前事务写过该表时走全扫描（读己之写）
        QueryPlanner.Plan plan = dbManager.getTransactionManager().currentTxnWroteTable(tableName)
                ? new QueryPlanner.Plan(null, null, false)
                : QueryPlanner.pickPlan(indexable, schema, where);

        String access;
        String keyInfo = "";
        String condInfo;
        String rows;
        if (plan.indexUsed()) {
            access = "INDEX SEEK";
            ColumnDefinition cd = findColumn(schema, plan.column());
            String key = QueryPlanner.IndexKey.keyFor(cd, plan.key());
            keyInfo = plan.column() + " (key=" + key
                    + (idx.isBuilt(tableName, plan.column()) ? "" : ", 尚未构建: 首次使用将全表扫描构建") + ")";
            condInfo = plan.column() + " = " + plan.key();
            int candidates = idx.lookup(tableName, plan.column(), plan.key()).size();
            rows = candidates + " candidate row(s)";
        } else {
            access = "FULL TABLE SCAN";
            condInfo = where != null ? "WHERE 逐行求值" : "无条件";
            rows = "up to " + (tableManager.getMaxZ(tableName) - 1) + " row(s)";
        }

        QueryResult result = QueryResult.ok("1 row(s)");
        result.setColumns(List.of("table", "statement", "access", "key", "condition", "rows"));
        result.setRows(List.of(List.of(tableName, kind, access, keyInfo, condInfo, rows)));
        result.setRowCount(1);
        return result;
    }

    private QueryResult executeCreateIndex(CreateIndexStatement stmt) {
        dbManager.getSchemaManager().addIndex(stmt.tableName, stmt.indexName, stmt.columnName);
        return QueryResult.ok("Index '" + stmt.indexName + "' created on '"
                + stmt.tableName + "(" + stmt.columnName + ")'（等值查询将走内存索引，EXPLAIN 可查看计划）");
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

    /**
     * 将表达式求值结果转换为列类型（UPDATE SET 用）。null 直通。
     * 字符串按列类型严格解析（报错信息与 parseValue 一致）；数值按四舍五入取整。
     */
    private Object coerceValue(Object v, String dataType) {
        if (v == null) return null;
        String typeBase = dataType.split("\\(")[0].toUpperCase();

        switch (typeBase) {
            case "INT", "INTEGER": {
                long lv = requireIntegral(v, typeBase);
                if (lv < Integer.MIN_VALUE || lv > Integer.MAX_VALUE) {
                    throw new SqlParser.ParseException("Invalid " + typeBase + " value: '" + v + "' (out of INT range)");
                }
                return (int) lv;
            }
            case "BIGINT":
                return requireIntegral(v, typeBase);
            case "FLOAT":
                return (float) requireDouble(v, typeBase);
            case "DOUBLE":
                return requireDouble(v, typeBase);
            case "BOOLEAN", "BOOL":
                if (v instanceof Boolean b) return b;
                return "true".equalsIgnoreCase(v.toString()) || "1".equals(v.toString());
            case "VARCHAR", "TEXT", "STRING":
                return v.toString();
            case "DATE", "DATETIME": {
                try {
                    return Long.parseLong(v.toString());
                } catch (NumberFormatException e) {
                    return v.toString();
                }
            }
            default:
                return v.toString();
        }
    }

    private long requireIntegral(Object v, String typeBase) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new SqlParser.ParseException("Invalid " + typeBase + " value: '" + v + "'");
            }
            return Math.round(d);
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            try {
                return Math.round(Double.parseDouble(v.toString().trim()));
            } catch (NumberFormatException e2) {
                throw new SqlParser.ParseException("Invalid " + typeBase + " value: '" + v + "'");
            }
        }
    }

    private double requireDouble(Object v, String typeBase) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new SqlParser.ParseException("Invalid " + typeBase + " value: '" + v + "'");
        }
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
