package com.minecraftdb.sql.ast;

import java.util.List;

public class SelectStatement extends Statement {
    public final List<String> columns; // empty or ["*"] for SELECT *
    public final String tableName;
    public final WhereClause whereClause;

    public SelectStatement(List<String> columns, String tableName, WhereClause whereClause) {
        this.columns = columns;
        this.tableName = tableName;
        this.whereClause = whereClause;
    }

    public boolean isSelectAll() {
        return columns.isEmpty() || (columns.size() == 1 && "*".equals(columns.get(0)));
    }

    @Override
    public Type getType() {
        return Type.SELECT;
    }
}
