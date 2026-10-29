package com.minecraftdb.sql.ast;

import java.util.List;

public class SelectStatement extends Statement {

    /** ORDER BY 项：列名与方向 */
    public record OrderBy(String column, boolean ascending) {}

    public final List<String> columns; // empty or ["*"] for SELECT *
    public final String tableName;
    public final WhereClause whereClause;
    public final List<OrderBy> orderBy;
    public final Integer limit;
    public final Integer offset;

    public SelectStatement(List<String> columns, String tableName, WhereClause whereClause) {
        this(columns, tableName, whereClause, List.of(), null, null);
    }

    public SelectStatement(List<String> columns, String tableName, WhereClause whereClause,
                           List<OrderBy> orderBy, Integer limit, Integer offset) {
        this.columns = columns;
        this.tableName = tableName;
        this.whereClause = whereClause;
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
