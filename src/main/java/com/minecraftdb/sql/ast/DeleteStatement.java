package com.minecraftdb.sql.ast;

public class DeleteStatement extends Statement {
    public final String tableName;
    public final WhereClause whereClause;

    public DeleteStatement(String tableName, WhereClause whereClause) {
        this.tableName = tableName;
        this.whereClause = whereClause;
    }

    @Override
    public Type getType() {
        return Type.DELETE;
    }
}
