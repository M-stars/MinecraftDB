package com.minecraftdb.sql.ast;

public class CreateIndexStatement extends Statement {
    public final String indexName;
    public final String tableName;
    public final String columnName;

    public CreateIndexStatement(String indexName, String tableName, String columnName) {
        this.indexName = indexName;
        this.tableName = tableName;
        this.columnName = columnName;
    }

    @Override
    public Type getType() {
        return Type.CREATE_INDEX;
    }
}
