package com.minecraftdb.sql.ast;

public class ShowCreateTableStatement extends Statement {
    public final String tableName;

    public ShowCreateTableStatement(String tableName) {
        this.tableName = tableName;
    }

    @Override
    public Type getType() {
        return Type.SHOW_CREATE_TABLE;
    }
}
