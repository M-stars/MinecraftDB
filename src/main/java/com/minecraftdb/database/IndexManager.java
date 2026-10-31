package com.minecraftdb.database;

import com.minecraftdb.sql.QueryPlanner;
import com.minecraftdb.sql.ast.ColumnDefinition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 内存索引管理器：为「有索引元数据的列 + 主键/自增列」维护 列值 → 行号(z) 的哈希映射，
 * 让等值查询与主键唯一性检查从全表扫描降为 O(1) 查找。
 *
 * <p>生命周期：懒构建（第一次被查询计划选中时全表扫描一次），之后由 INSERT/UPDATE/DELETE
 * 增量维护。事务回滚/会话中断会让方块状态回退而索引无法回放——因此回滚路径按
 * 事务触碰过的表整表失效，下次查询重新构建。TRUNCATE/DROP/ALTER 同理。
 *
 * <p>所有语句在服务端主线程串行执行，因此本类无需并发结构。
 */
public class IndexManager {

    private static final Logger LOGGER = LogManager.getLogger("MinecraftDB");

    /** table → column → key → z 集合（TreeSet 保持行号有序） */
    private final Map<String, Map<String, Map<String, TreeSet<Long>>>> indexes = new HashMap<>();
    /** table → 已构建索引的列集合 */
    private final Map<String, Set<String>> builtColumns = new HashMap<>();

    private final TableManager tableManager;
    private final SchemaManager schemaManager;

    public IndexManager(TableManager tableManager, SchemaManager schemaManager) {
        this.tableManager = tableManager;
        this.schemaManager = schemaManager;
    }

    // === 可索引列 ===

    /**
     * 表的可索引列 = SHOW INDEXES 元数据中的列 + 主键列 + 自增列 + UNIQUE 列。
     * 主键/自增/UNIQUE 列的索引同时服务唯一性检查（替代全列扫描）。
     */
    public Set<String> indexableColumns(String tableName) {
        Set<String> cols = new java.util.LinkedHashSet<>();
        Map<String, String> meta = schemaManager.getIndexes(tableName);
        if (meta != null) cols.addAll(meta.values());
        List<ColumnDefinition> schema = schemaManager.getSchema(tableName);
        if (schema != null) {
            for (ColumnDefinition cd : schema) {
                if (cd.isPrimaryKey() || cd.autoIncrement || cd.isUnique()) cols.add(cd.name);
            }
        }
        return cols;
    }

    public boolean isIndexable(String tableName, String columnName) {
        for (String c : indexableColumns(tableName)) {
            if (c.equalsIgnoreCase(columnName)) return true;
        }
        return false;
    }

    // === 查询（懒构建） ===

    /**
     * 等值查找：返回列值等于 value 的行号集合（可能为空集，不会为 null）。
     * 索引未构建时先全表扫描构建一次。
     */
    public Set<Long> lookup(String tableName, String columnName, Object value) {
        ColumnDefinition cd = findColumn(schemaManager.getSchema(tableName), columnName);
        if (cd == null) return Collections.emptySet();
        String key = QueryPlanner.IndexKey.keyFor(cd, value);
        if (key == null) return Collections.emptySet();

        Map<String, Map<String, TreeSet<Long>>> tableIdx = indexes.computeIfAbsent(tableName, k -> new HashMap<>());
        Set<String> built = builtColumns.computeIfAbsent(tableName, k -> new java.util.HashSet<>());
        if (!built.contains(columnName.toLowerCase(Locale.ROOT))) {
            buildColumn(tableName, cd, tableIdx, built);
        }
        TreeSet<Long> zs = tableIdx.get(columnName.toLowerCase(Locale.ROOT)) != null
                ? tableIdx.get(columnName.toLowerCase(Locale.ROOT)).get(key)
                : null;
        return zs != null ? zs : Collections.emptySet();
    }

    /** 供 EXPLAIN 显示：该列索引是否已构建（未构建则首次使用会触发全表扫描） */
    public boolean isBuilt(String tableName, String columnName) {
        Set<String> built = builtColumns.get(tableName);
        return built != null && built.contains(columnName.toLowerCase(Locale.ROOT));
    }

    /** 全表扫描构建单列索引 */
    private void buildColumn(String tableName, ColumnDefinition cd,
                             Map<String, Map<String, TreeSet<Long>>> tableIdx, Set<String> built) {
        String colKey = cd.name.toLowerCase(Locale.ROOT);
        Map<String, TreeSet<Long>> colIdx = new HashMap<>();
        long maxZ = tableManager.getMaxZ(tableName);
        List<ColumnDefinition> schema = schemaManager.getSchema(tableName);
        int builtRows = 0;
        for (int z = 1; z < maxZ; z++) {
            Map<String, Object> row = tableManager.readRow(tableName, schema, z);
            if (row == null || row.isEmpty()) continue;
            String key = QueryPlanner.IndexKey.keyFor(cd, row.get(cd.name));
            if (key != null) {
                colIdx.computeIfAbsent(key, k -> new TreeSet<>()).add((long) z);
            }
            builtRows++;
        }
        tableIdx.put(colKey, colIdx);
        built.add(colKey);
        LOGGER.debug("Index built for {}.{} ({} rows indexed)", tableName, cd.name, builtRows);
    }

    // === 增量维护 ===

    /** INSERT 后调用：把新行的可索引列登记进索引 */
    public void onInsert(String tableName, long z, Map<String, Object> rowData) {
        for (String col : indexableColumns(tableName)) {
            Object value = rowData.get(col);
            if (value == null) continue;
            ColumnDefinition cd = findColumn(schemaManager.getSchema(tableName), col);
            if (cd == null) continue;
            String key = QueryPlanner.IndexKey.keyFor(cd, value);
            if (key == null) continue;
            Map<String, TreeSet<Long>> colIdx = columnOrNull(tableName, col);
            if (colIdx != null) {
                colIdx.computeIfAbsent(key, k -> new TreeSet<>()).add(z);
            }
        }
    }

    /**
     * UPDATE 后调用：merged 为「本行更新后的完整行」。只重登记值发生变化的可索引列。
     * 索引未构建时无需操作（下次查询会全量构建）。
     */
    public void onUpdate(String tableName, long z, Map<String, Object> oldRow, Map<String, Object> mergedRow) {
        for (String col : indexableColumns(tableName)) {
            Object oldVal = oldRow.get(col);
            Object newVal = mergedRow.get(col);
            if (java.util.Objects.equals(oldVal, newVal)) continue;
            ColumnDefinition cd = findColumn(schemaManager.getSchema(tableName), col);
            if (cd == null) continue;
            Map<String, TreeSet<Long>> colIdx = columnOrNull(tableName, col);
            if (colIdx == null) continue;
            String oldKey = QueryPlanner.IndexKey.keyFor(cd, oldVal);
            if (oldKey != null) {
                TreeSet<Long> zs = colIdx.get(oldKey);
                if (zs != null) zs.remove(z);
            }
            String newKey = QueryPlanner.IndexKey.keyFor(cd, newVal);
            if (newKey != null) {
                colIdx.computeIfAbsent(newKey, k -> new TreeSet<>()).add(z);
            }
        }
    }

    /** DELETE 后调用：从所有已构建列索引中移除该行 */
    public void onDelete(String tableName, long z, Map<String, Object> oldRow) {
        for (String col : indexableColumns(tableName)) {
            ColumnDefinition cd = findColumn(schemaManager.getSchema(tableName), col);
            if (cd == null) continue;
            String key = QueryPlanner.IndexKey.keyFor(cd, oldRow.get(col));
            if (key == null) continue;
            Map<String, TreeSet<Long>> colIdx = columnOrNull(tableName, col);
            if (colIdx != null) {
                TreeSet<Long> zs = colIdx.get(key);
                if (zs != null) zs.remove(z);
            }
        }
    }

    // === 失效 ===

    /** 方块状态可能已与本索引不一致（回滚/TRUNCATE/ALTER），整表失效，下次查询重建 */
    public void invalidateTable(String tableName) {
        indexes.remove(tableName);
        builtColumns.remove(tableName);
    }

    public void invalidateTables(Iterable<String> tableNames) {
        for (String t : tableNames) {
            invalidateTable(t);
        }
    }

    public void dropTable(String tableName) {
        invalidateTable(tableName);
    }

    private Map<String, TreeSet<Long>> columnOrNull(String tableName, String columnName) {
        Map<String, Map<String, TreeSet<Long>>> tableIdx = indexes.get(tableName);
        if (tableIdx == null) return null;
        return tableIdx.get(columnName.toLowerCase(Locale.ROOT));
    }

    private static ColumnDefinition findColumn(List<ColumnDefinition> schema, String name) {
        if (schema == null) return null;
        for (ColumnDefinition cd : schema) {
            if (cd.name.equalsIgnoreCase(name)) return cd;
        }
        return null;
    }
}
