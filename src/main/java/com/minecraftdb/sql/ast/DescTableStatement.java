package com.minecraftdb.sql.ast;

public class DescTableStatement extends Statement {
    public final String tableName;

    public DescTableStatement(String tableName) {
        this.tableName = tableName;
    }

    @Override
    public Type getType() {
        return Type.DESC_TABLE;
    }
}
