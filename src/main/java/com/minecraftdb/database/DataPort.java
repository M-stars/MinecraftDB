package com.minecraftdb.database;

import com.minecraftdb.sql.SqlExecutor;
import com.minecraftdb.sql.ast.ColumnDefinition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据进出口：CSV 导入/导出、SQL dump（mysqldump 风格）。
 *
 * <p>所有方法必须在服务端主线程调用（HTTP 路径经 DatabaseManager.callOnServerThread）。
 * CSV 导入经由完整 SQL INSERT 路径执行——约束（主键/UNIQUE/外键/NOT NULL）、
 * 事务与 WAL 全部生效；按批自动提交。
 */
public class DataPort {

    private static final Logger LOGGER = LogManager.getLogger("MinecraftDB");

    /** 单批 INSERT 的行数 */
    private static final int INSERT_BATCH = 100;

    private final DatabaseManager dbManager;

    public DataPort(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    // === CSV 导出 ===

    /** 导出单表为 CSV（首行为表头；单元格按 RFC 4180 转义） */
    public String exportCsv(String tableName) {
        SchemaManager schemaManager = dbManager.getSchemaManager();
        TableManager tm = dbManager.getTableManager();
        List<ColumnDefinition> schema = requireSchema(tableName);
        StringBuilder sb = new StringBuilder();
        List<String> headers = new ArrayList<>();
        for (ColumnDefinition cd : schema) headers.add(cd.name);
        appendCsvRow(sb, headers);

        long maxZ = tm.getMaxZ(tableName);
        for (int z = 1; z < maxZ; z++) {
            Map<String, Object> row = tm.readRow(tableName, schema, z);
            if (row == null || row.isEmpty()) continue;
            List<String> cells = new ArrayList<>(schema.size());
            for (ColumnDefinition cd : schema) {
                Object v = row.get(cd.name);
                cells.add(v == null ? "" : String.valueOf(v));
            }
            appendCsvRow(sb, cells);
        }
        return sb.toString();
    }

    // === SQL dump ===

    /** 导出单表或整库为 SQL（CREATE TABLE + INSERT；tableName 为 null 时整库） */
    public String exportSqlDump(String tableName) {
        SchemaManager schemaManager = dbManager.getSchemaManager();
        Set<String> tables = dbManager.getTableManager().listTables();
        StringBuilder sb = new StringBuilder();
        sb.append("-- MinecraftDB dump（").append(dbManager.getDatabaseName()).append("）\n");
        sb.append("-- 生成时间: ").append(java.time.LocalDateTime.now()).append("\n\n");

        List<String> ordered = new ArrayList<>(tables);
        java.util.Collections.sort(ordered);
        for (String t : ordered) {
            if (tableName != null && !t.equalsIgnoreCase(tableName)) continue;
            List<ColumnDefinition> schema = schemaManager.getSchema(t);
            if (schema == null) continue;
            appendTableDump(sb, t, schema);
        }
        return sb.toString();
    }

    private void appendTableDump(StringBuilder sb, String tableName, List<ColumnDefinition> schema) {
        TableManager tm = dbManager.getTableManager();
        sb.append(executeShowCreateSql(tableName)).append("\n\n");

        long maxZ = tm.getMaxZ(tableName);
        List<List<Object>> batch = new ArrayList<>();
        int rows = 0;
        for (int z = 1; z < maxZ; z++) {
            Map<String, Object> row = tm.readRow(tableName, schema, z);
            if (row == null || row.isEmpty()) continue;
            List<Object> values = new ArrayList<>(schema.size());
            for (ColumnDefinition cd : schema) values.add(row.get(cd.name));
            batch.add(values);
            rows++;
            if (batch.size() >= INSERT_BATCH) {
                appendInsertBatch(sb, tableName, schema, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            appendInsertBatch(sb, tableName, schema, batch);
        }
        if (rows == 0) {
            sb.append("-- （无数据）\n");
        }
        sb.append("\n");
    }

    /** SHOW CREATE TABLE 文本（经 SQL 通道取回，复用执行器渲染逻辑） */
    private String executeShowCreateSql(String tableName) {
        SqlExecutor.QueryResult r = dbManager.getSqlExecutor().execute("SHOW CREATE TABLE " + tableName);
        if (r.isSuccess() && !r.getRows().isEmpty()) {
            Object sql = r.getRows().get(0).get(1);
            return sql != null ? sql.toString() : "";
        }
        return "-- 无法生成 CREATE TABLE: " + tableName;
    }

    private void appendInsertBatch(StringBuilder sb, String tableName,
                                   List<ColumnDefinition> schema, List<List<Object>> batch) {
        sb.append("INSERT INTO ").append(tableName).append(" (");
        for (int i = 0; i < schema.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(schema.get(i).name);
        }
        sb.append(") VALUES\n");
        for (int r = 0; r < batch.size(); r++) {
            sb.append("  (");
            List<Object> row = batch.get(r);
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(sqlLiteral(row.get(i), schema.get(i)));
            }
            sb.append(")").append(r < batch.size() - 1 ? "," : ";").append("\n");
        }
    }

    private String sqlLiteral(Object v, ColumnDefinition cd) {
        if (v == null) return "NULL";
        String typeBase = cd.dataType.split("\\(")[0].toUpperCase();
        if (typeBase.equals("INT") || typeBase.equals("INTEGER") || typeBase.equals("BIGINT")
                || typeBase.equals("FLOAT") || typeBase.equals("DOUBLE")
                || typeBase.equals("DATE") || typeBase.equals("DATETIME")
                || typeBase.equals("BOOLEAN")) {
            return String.valueOf(v); // 数值/日期戳/布尔不带引号
        }
        return "'" + String.valueOf(v).replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    // === CSV 导入 ===

    /**
     * 导入 CSV 到表。表不存在且 autoCreate 时按表头+数据推断列类型建表；
     * 表存在时表头列须为表的子集（顺序不限，未提供列走 DEFAULT/NULL）。
     * 返回插入行数。CSV 首行必须是表头。
     */
    public int importCsv(String tableName, String csv, boolean autoCreate) {
        List<List<String>> records = parseCsv(csv);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("CSV 内容为空");
        }
        List<String> header = records.get(0);
        List<List<String>> dataRows = records.subList(1, records.size());

        SchemaManager schemaManager = dbManager.getSchemaManager();
        if (!dbManager.getTableManager().tableExists(tableName)) {
            if (!autoCreate) {
                throw new IllegalArgumentException("表 '" + tableName + "' 不存在（可用 ?autoCreate=true 自动建表）");
            }
            createFromHeader(tableName, header, dataRows);
        } else {
            // 校验表头列为表列子集
            List<ColumnDefinition> schema = requireSchema(tableName);
            for (String h : header) {
                if (findColumn(schema, h) == null) {
                    throw new IllegalArgumentException("CSV 表头列 '" + h + "' 不是表 '" + tableName + "' 的列");
                }
            }
        }

        // 分批多行 INSERT（经完整 SQL 路径：约束/事务/WAL 全部生效）
        int inserted = 0;
        for (int from = 0; from < dataRows.size(); from += INSERT_BATCH) {
            List<List<String>> batch = dataRows.subList(from, Math.min(from + INSERT_BATCH, dataRows.size()));
            if (batch.isEmpty()) break;
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
            for (int i = 0; i < header.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append(header.get(i));
            }
            sql.append(") VALUES ");
            for (int r = 0; r < batch.size(); r++) {
                if (r > 0) sql.append(", ");
                sql.append("(");
                List<String> row = batch.get(r);
                for (int i = 0; i < header.size(); i++) {
                    if (i > 0) sql.append(", ");
                    String cell = i < row.size() ? row.get(i) : null;
                    sql.append(csvCellToLiteral(cell));
                }
                sql.append(")");
            }
            SqlExecutor.QueryResult result = dbManager.getSqlExecutor().execute(sql.toString());
            if (!result.isSuccess()) {
                throw new IllegalStateException("第 " + (from + batch.size()) + " 行附近导入失败: "
                        + result.getMessage() + "（已插入 " + inserted + " 行）");
            }
            inserted += batch.size();
        }
        return inserted;
    }

    /** 按表头与数据推断列类型建表（全整数→INT，含小数→DOUBLE，true/false→BOOLEAN，否则 VARCHAR(255)） */
    private void createFromHeader(String tableName, List<String> header, List<List<String>> dataRows) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(tableName).append(" (");
        for (int c = 0; c < header.size(); c++) {
            if (c > 0) sql.append(", ");
            sql.append(header.get(c)).append(" ").append(inferType(dataRows, c));
        }
        sql.append(")");
        SqlExecutor.QueryResult result = dbManager.getSqlExecutor().execute(sql.toString());
        if (!result.isSuccess()) {
            throw new IllegalStateException("自动建表失败: " + result.getMessage());
        }
        LOGGER.info("Import auto-created table {} from CSV header", tableName);
    }

    private String inferType(List<List<String>> rows, int col) {
        boolean allInt = true;
        boolean allNumeric = true;
        boolean allBool = true;
        int sampled = 0;
        for (List<String> row : rows) {
            if (col >= row.size()) continue;
            String v = row.get(col);
            if (v == null || v.isEmpty()) continue;
            sampled++;
            if (allInt && !isParsableInt(v)) allInt = false;
            if (allNumeric && !isParsableDouble(v)) allNumeric = false;
            if (allBool && !v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) allBool = false;
            if (!allInt && !allNumeric && !allBool) break;
        }
        if (sampled == 0) return "VARCHAR(255)";
        if (allInt) return "INT";
        if (allNumeric) return "DOUBLE";
        if (allBool) return "BOOLEAN";
        return "VARCHAR(255)";
    }

    private static boolean isParsableInt(String v) {
        try {
            Integer.parseInt(v.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isParsableDouble(String v) {
        try {
            Double.parseDouble(v.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** CSV 单元格 → SQL 字面量：空单元格 = NULL，数字不带引号，其余带引号转义 */
    private String csvCellToLiteral(String cell) {
        if (cell == null || cell.isEmpty()) return "NULL";
        if (isParsableInt(cell) || isParsableDouble(cell)) return cell.trim();
        return "'" + cell.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    // === CSV 解析（RFC 4180：引号、逗号、换行、双引号转义） ===

    public static List<List<String>> parseCsv(String csv) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean cellStarted = false;
        String text = csv != null ? csv : "";

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
                cellStarted = true;
            } else if (c == ',') {
                current.add(cell.toString());
                cell.setLength(0);
                cellStarted = false;
            } else if (c == '\r') {
                // 跳过（\n 统一处理）
            } else if (c == '\n') {
                if (cellStarted || cell.length() > 0 || !current.isEmpty()) {
                    current.add(cell.toString());
                    cell.setLength(0);
                    records.add(current);
                    current = new ArrayList<>();
                    cellStarted = false;
                }
            } else {
                cell.append(c);
                cellStarted = true;
            }
        }
        if (cellStarted || cell.length() > 0 || !current.isEmpty()) {
            current.add(cell.toString());
            records.add(current);
        }
        return records;
    }

    private static void appendCsvRow(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            String v = cells.get(i);
            boolean needQuote = v != null && (v.contains(",") || v.contains("\"")
                    || v.contains("\n") || v.contains("\r"));
            if (needQuote) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        sb.append('\n');
    }

    private List<ColumnDefinition> requireSchema(String tableName) {
        List<ColumnDefinition> schema = dbManager.getSchemaManager().getSchema(tableName);
        if (schema == null) {
            throw new IllegalArgumentException("Table '" + tableName + "' does not exist");
        }
        return schema;
    }

    private static ColumnDefinition findColumn(List<ColumnDefinition> schema, String name) {
        for (ColumnDefinition cd : schema) {
            if (cd.name.equalsIgnoreCase(name)) return cd;
        }
        return null;
    }
}
