package com.minecraftdb.sql.ast;

public abstract class Statement {

    public enum Type {
        CREATE_TABLE,
        DROP_TABLE,
        ALTER_TABLE,
        TRUNCATE_TABLE,
        INSERT,
        SELECT,
        UPDATE,
        DELETE,
        SHOW_TABLES,
        DESC_TABLE,
        SHOW_CREATE_TABLE,
        BEGIN,
        COMMIT,
        ROLLBACK,
        CREATE_INDEX,
        SHOW_INDEXES,
        EXPLAIN
    }

    public abstract Type getType();
}
