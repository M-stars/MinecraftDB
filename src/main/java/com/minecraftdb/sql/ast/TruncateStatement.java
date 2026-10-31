package com.minecraftdb.sql.ast;

/**
 * TRUNCATE [TABLE] name —— DDL 语义：清空全部行并重置自增（nextZ 归 1），
 * 隐式提交、不可回滚（与 MySQL 一致），事务内执行直接报错。
 */
public class TruncateStatement extends Statement {
    public final String tableName;

    public TruncateStatement(String tableName) {
        this.tableName = tableName;
    }

    @Override
    public Type getType() {
        return Type.TRUNCATE_TABLE;
    }
}
