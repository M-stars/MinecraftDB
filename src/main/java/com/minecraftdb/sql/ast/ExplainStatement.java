package com.minecraftdb.sql.ast;

/**
 * EXPLAIN SELECT/UPDATE/DELETE：不执行语句，返回查询计划
 * （访问方式 = 索引查找或全表扫描、命中的索引列与条件、候选行数）。
 */
public class ExplainStatement extends Statement {
    public final Statement inner;

    public ExplainStatement(Statement inner) {
        this.inner = inner;
    }

    @Override
    public Type getType() {
        return Type.EXPLAIN;
    }
}
