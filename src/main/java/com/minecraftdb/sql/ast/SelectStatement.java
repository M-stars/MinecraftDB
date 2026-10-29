package com.minecraftdb.sql.ast;

import java.util.List;

public class SelectStatement extends Statement {

    /** ORDER BY 项：列名与方向 */
    public record OrderBy(String column, boolean ascending) {}

    /**
     * SELECT 项：function 为 null 表示普通列；function ∈ {COUNT,SUM,AVG,MIN,MAX}，
     * column 为 "*"（仅 COUNT）或列名；label 为输出列名（可被 AS 别名覆盖）。
     */
    public record SelectItem(String function, String column, String label) {}

    public final List<String> columns; // empty or ["*"] for SELECT *（原始写法，与 selectItems 对应）
    public final String tableName;
    public final WhereClause whereClause;
    public final List<SelectItem> selectItems; // null 表示未启用（旧构造器）
    public final List<String> groupBy;
    public final boolean distinct;
    public final List<OrderBy> orderBy;
    public final Integer limit;
    public final Integer offset;

    public SelectStatement(List<String> columns, String tableName, WhereClause whereClause) {
        this(columns, tableName, whereClause, null, List.of(), false, List.of(), null, null);
    }

    public SelectStatement(List<String> columns, String tableName, WhereClause whereClause,
                           List<SelectItem> selectItems, List<String> groupBy, boolean distinct,
                           List<OrderBy> orderBy, Integer limit, Integer offset) {
        this.columns = columns;
        this.tableName = tableName;
        this.whereClause = whereClause;
        this.selectItems = selectItems;
        this.groupBy = groupBy;
        this.distinct = distinct;
        this.orderBy = orderBy;
        this.limit = limit;
        this.offset = offset;
    }

    public boolean isSelectAll() {
        return columns.isEmpty() || (columns.size() == 1 && "*".equals(columns.get(0)));
    }

    @Override
    public Type getType() {
        return Type.SELECT;
    }
}
