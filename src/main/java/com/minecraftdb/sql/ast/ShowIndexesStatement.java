package com.minecraftdb.sql.ast;

public class ShowIndexesStatement extends Statement {
    public final String tableName;

    public ShowIndexesStatement(String tableName) {
        this.tableName = tableName;
    }

    @Override
    public Type getType() {
        return Type.SHOW_INDEXES;
    }
}
