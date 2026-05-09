package com.minecraftdb.sql.ast;

import java.util.Map;

public class UpdateStatement extends Statement {
    public final String tableName;
    public final Map<String, String> assignments; // column → value
    public final WhereClause whereClause;

    public UpdateStatement(String tableName, Map<String, String> assignments, WhereClause whereClause) {
        this.tableName = tableName;
        this.assignments = assignments;
        this.whereClause = whereClause;
    }

    @Override
    public Type getType() {
        return Type.UPDATE;
    }
}
