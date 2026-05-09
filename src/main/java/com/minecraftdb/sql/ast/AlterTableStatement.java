package com.minecraftdb.sql.ast;

public class AlterTableStatement extends Statement {
    public final String tableName;
    public final String action; // ADD or DROP
    public final ColumnDefinition column;
    public final String dropColumnName; // used when action == DROP

    public AlterTableStatement(String tableName, String action, ColumnDefinition column, String dropColumnName) {
        this.tableName = tableName;
        this.action = action.toUpperCase();
        this.column = column;
        this.dropColumnName = dropColumnName;
    }

    @Override
    public Type getType() {
        return Type.ALTER_TABLE;
    }
}
