package com.minecraftdb.sql.ast;

import java.util.List;

public class CreateTableStatement extends Statement {
    public final String tableName;
    public final List<ColumnDefinition> columns;
    public boolean ifNotExists;

    public CreateTableStatement(String tableName, List<ColumnDefinition> columns) {
        this.tableName = tableName;
        this.columns = columns;
    }

    @Override
    public Type getType() {
        return Type.CREATE_TABLE;
    }
}
