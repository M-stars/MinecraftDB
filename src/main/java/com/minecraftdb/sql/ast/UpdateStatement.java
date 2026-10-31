package com.minecraftdb.sql.ast;

import java.util.Map;

public class UpdateStatement extends Statement {
    public final String tableName;
    public final Map<String, Expression> assignments; // column → 赋值表达式（支持 col = col + 1、函数等）
    public final WhereClause whereClause;

    public UpdateStatement(String tableName, Map<String, Expression> assignments, WhereClause whereClause) {
        this.tableName = tableName;
        this.assignments = assignments;
        this.whereClause = whereClause;
    }

    @Override
    public Type getType() {
        return Type.UPDATE;
    }
}
