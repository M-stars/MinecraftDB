package com.minecraftdb.sql.ast;

import java.util.List;

public class SelectStatement extends Statement {

    /** ORDER BY 项：列名与方向 */
    public record OrderBy(String column, boolean ascending) {}

    /**
     * FROM 中的表引用：表名 + 可选别名。别名（或表名）作为该表列的限定前缀。
     */
    public record TableRef(String tableName, String alias) {
        public String qualifier() {
            return alias != null ? alias : tableName;
        }
    }

    /**
     * JOIN 子句：类型（INNER/LEFT）+ 右表 + ON 条件。
     */
    public record JoinSpec(String type, TableRef table, WhereClause on) {}

    /**
     * SELECT 投影项：expr 为任意表达式（列引用、聚合、标量函数、算术），
     * label 为输出列名（可被 AS 别名覆盖）。
     * function()/column() 为兼容旧结构（function, column）的便捷视图。
     */
    public record SelectItem(Expression expr, String label) {

        /** 聚合函数名；expr 非聚合调用时为 null */
        public String function() {
            if (expr instanceof Expression.Func f
                    && Expression.AGGREGATE_FUNCTIONS.contains(f.name)) {
                return f.name;
            }
            return null;
        }

        /**
         * 旧结构的列视图：聚合/列引用取列名，COUNT(*) 与 SELECT * 取 "*"，
         * 复杂表达式无对应列名时为 null。
         */
        public String column() {
            if (expr instanceof Expression.Func f) {
                if (f.args.size() == 1) {
                    Expression a = f.args.get(0);
                    if (a instanceof Expression.Literal l && "*".equals(l.typed)) return "*";
                    if (a instanceof Expression.ColumnRef c) return c.name;
                }
                return null;
            }
            if (expr instanceof Expression.ColumnRef c) return c.name;
            if (expr instanceof Expression.Literal l && "*".equals(l.typed)) return "*";
            return null;
        }
    }

    public final List<String> columns; // 每个投影项的输出列名（与 selectItems 对应，["*"] 表示 SELECT *）
    public final String tableName;     // 主表名（无 JOIN 时的唯一表）
    public final TableRef mainTable;   // 主表引用（别名支持）；null 表示旧构造器
    public final List<JoinSpec> joins; // JOIN 链；空列表 = 单表查询
    public final WhereClause whereClause;
    public final List<SelectItem> selectItems; // null 表示未启用（旧构造器）
    public final List<String> groupBy;
    public final WhereClause havingClause; // null 表示无 HAVING
    public final boolean distinct;
    public final List<OrderBy> orderBy;
    public final Integer limit;
    public final Integer offset;

    public SelectStatement(List<String> columns, String tableName, WhereClause whereClause) {
        this(columns, tableName, new TableRef(tableName, null), List.of(), whereClause,
                null, List.of(), null, false, List.of(), null, null);
    }

    public SelectStatement(List<String> columns, String tableName, WhereClause whereClause,
                           List<SelectItem> selectItems, List<String> groupBy, boolean distinct,
                           List<OrderBy> orderBy, Integer limit, Integer offset) {
        this(columns, tableName, new TableRef(tableName, null), List.of(), whereClause,
                selectItems, groupBy, null, distinct, orderBy, limit, offset);
    }

    public SelectStatement(List<String> columns, String tableName, WhereClause whereClause,
                           List<SelectItem> selectItems, List<String> groupBy, WhereClause havingClause,
                           boolean distinct, List<OrderBy> orderBy, Integer limit, Integer offset) {
        this(columns, tableName, new TableRef(tableName, null), List.of(), whereClause,
                selectItems, groupBy, havingClause, distinct, orderBy, limit, offset);
    }

    public SelectStatement(List<String> columns, String tableName, TableRef mainTable, List<JoinSpec> joins,
                           WhereClause whereClause, List<SelectItem> selectItems, List<String> groupBy,
                           WhereClause havingClause, boolean distinct, List<OrderBy> orderBy,
                           Integer limit, Integer offset) {
        this.columns = columns;
        this.tableName = tableName;
        this.mainTable = mainTable != null ? mainTable : new TableRef(tableName, null);
        this.joins = joins;
        this.whereClause = whereClause;
        this.selectItems = selectItems;
        this.groupBy = groupBy;
        this.havingClause = havingClause;
        this.distinct = distinct;
        this.orderBy = orderBy;
        this.limit = limit;
        this.offset = offset;
    }

    public boolean isJoinQuery() {
        return !joins.isEmpty();
    }

    public boolean isSelectAll() {
        return columns.isEmpty() || (columns.size() == 1 && "*".equals(columns.get(0)));
    }

    @Override
    public Type getType() {
        return Type.SELECT;
    }
}
