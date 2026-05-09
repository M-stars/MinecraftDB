package com.minecraftdb.sql.ast;

public class ShowTablesStatement extends Statement {
    @Override
    public Type getType() {
        return Type.SHOW_TABLES;
    }
}
