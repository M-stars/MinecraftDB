package com.minecraftdb.sql.ast;

/**
 * 标量子查询表达式 {@code (SELECT ...)}：出现在比较右侧、算术运算等表达式位置。
 *
 * <p>执行器在语句执行前将其求值并替换为 {@link Literal}（非相关子查询）；
 * 若求值后仍被 eval（编程错误）则抛出异常。相关子查询（引用外层列）不支持。
 */
public final class SubqueryExpression extends Expression {
    public final SelectStatement sub;

    public SubqueryExpression(SelectStatement sub) {
        this.sub = sub;
    }

    @Override
    public Object eval(Resolver resolver) {
        throw new IllegalStateException("Subquery must be resolved to a literal before evaluation");
    }

    @Override
    public boolean exists(Resolver resolver) {
        throw new IllegalStateException("Subquery must be resolved to a literal before evaluation");
    }

    @Override
    public String sqlText() {
        return "(SELECT ...)";
    }
}
