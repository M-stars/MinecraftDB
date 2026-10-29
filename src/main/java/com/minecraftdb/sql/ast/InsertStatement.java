package com.minecraftdb.sql.ast;

import java.util.List;

public class InsertStatement extends Statement {
    public final String tableName;
    public final List<String> columns;
    /** 多组 VALUES，每组与 columns 一一对应（单组 INSERT 也归一为单元素列表） */
    public final List<List<String>> valueRows;

    public InsertStatement(String tableName, List<String> columns, List<List<String>> valueRows) {
        this.tableName = tableName;
        this.columns = columns;
        this.valueRows = valueRows;
    }

    @Override
    public Type getType() {
        return Type.INSERT;
    }
}
