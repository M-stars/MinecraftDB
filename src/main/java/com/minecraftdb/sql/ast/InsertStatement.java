package com.minecraftdb.sql.ast;

import java.util.List;

public class InsertStatement extends Statement {
    public final String tableName;
    public final List<String> columns;
    public final List<String> values;

    public InsertStatement(String tableName, List<String> columns, List<String> values) {
        this.tableName = tableName;
        this.columns = columns;
        this.values = values;
    }

    @Override
    public Type getType() {
        return Type.INSERT;
    }
}
