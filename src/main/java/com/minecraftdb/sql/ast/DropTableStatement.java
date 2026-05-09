package com.minecraftdb.sql.ast;

public class DropTableStatement extends Statement {
    public final String tableName;

    public DropTableStatement(String tableName) {
        this.tableName = tableName;
    }

    @Override
    public Type getType() {
        return Type.DROP_TABLE;
    }
}
