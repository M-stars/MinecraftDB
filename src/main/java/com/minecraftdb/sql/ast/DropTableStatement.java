package com.minecraftdb.sql.ast;

public class DropTableStatement extends Statement {
    public final String tableName;
    public boolean ifExists;

    public DropTableStatement(String tableName) {
        this(tableName, false);
    }

    public DropTableStatement(String tableName, boolean ifExists) {
        this.tableName = tableName;
        this.ifExists = ifExists;
    }

    @Override
    public Type getType() {
        return Type.DROP_TABLE;
    }
}
