package com.minecraftdb.sql;

import com.minecraftdb.sql.ast.ColumnDefinition;
import com.minecraftdb.sql.ast.Expression;
import com.minecraftdb.sql.ast.WhereClause;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 查询计划器（纯函数，可脱离 Minecraft 单测）：
 * 把 WHERE 根结点拆成 AND 合取项，从中挑出可走内存索引的等值条件
 * {@code col = 常量}（列上有索引元数据时）。
 *
 * <p>索引候选集只是初筛——命中行仍会重新对完整 WHERE 求值，
 * 因此「索引键匹配但语义上不等」的边界（如布尔列与字符串字面量）不会产生错误结果，
 * 只可能回退为全扫描。
 */
public final class QueryPlanner {

    private QueryPlanner() {}

    /** 选中的访问路径 */
    public record Plan(
            String column,        // 索引列名（schema 大小写）
            Object key,           // 等值常量（Literal.typed）
            boolean indexUsed     // true=索引查找，false=全表扫描
    ) {}

    /**
     * 为 WHERE 选计划。indexedColumns 为可索引列名（大小写不敏感比对）。
     * where 为 null 或无可索引条件时返回全扫描计划。
     */
    public static Plan pickPlan(Collection<String> indexedColumns, List<ColumnDefinition> schema, WhereClause where) {
        if (where == null || indexedColumns.isEmpty()) {
            return new Plan(null, null, false);
        }
        for (WhereClause.Node node : where.splitConjuncts()) {
            if (!(node instanceof WhereClause.OpCondition op) || !"=".equals(op.getOperator())) continue;
            if (!(op.getOperand() instanceof Expression.ColumnRef colRef)) continue;
            if (!(op.getValue() instanceof Expression.Literal lit)) continue;
            Object typed = lit.typed;
            if (typed == null || "*".equals(typed)) continue;

            ColumnDefinition cd = findColumn(schema, colRef.name);
            if (cd == null) continue;
            if (!containsIgnoreCase(indexedColumns, cd.name)) continue;

            // 键必须可归一化且与列类型一致（布尔列只接受布尔/true/false 字面量）
            if (IndexKey.keyFor(cd, typed) == null) continue;

            return new Plan(cd.name, typed, true);
        }
        return new Plan(null, null, false);
    }

    private static ColumnDefinition findColumn(List<ColumnDefinition> schema, String name) {
        for (ColumnDefinition cd : schema) {
            if (cd.name.equalsIgnoreCase(name)) return cd;
        }
        return null;
    }

    private static boolean containsIgnoreCase(Collection<String> list, String name) {
        for (String s : list) {
            if (s.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * 索引键归一化：同一列上「存储值」与「查询字面量」必须映射到相同键。
     * 与 WhereClause.OpCondition 的相等语义保持一致：
     * 数值按数值比较，布尔列兼容 'true'/'false' 字符串，其余按字符串。
     * 返回 null 表示该值不可索引（调用方回退全扫描）。
     */
    public static final class IndexKey {

        private IndexKey() {}

        public static String keyFor(ColumnDefinition cd, Object v) {
            if (v == null) return null;
            String typeBase = cd.dataType.split("\\(")[0].toUpperCase(Locale.ROOT);
            return switch (typeBase) {
                case "INT", "INTEGER", "BIGINT", "FLOAT", "DOUBLE" -> numericKey(v);
                case "BOOLEAN", "BOOL" -> booleanKey(v);
                case "VARCHAR", "TEXT", "STRING", "DATE", "DATETIME" -> "s:" + v;
                default -> "s:" + v;
            };
        }

        private static String numericKey(Object v) {
            if (v instanceof Number n) return "n:" + n.doubleValue();
            try {
                return "n:" + Double.parseDouble(v.toString());
            } catch (NumberFormatException e) {
                return null; // 数值列上不可解析的字符串：条件在行级比较时为 false，直接全扫描
            }
        }

        private static String booleanKey(Object v) {
            if (v instanceof Boolean b) return "b:" + b;
            String s = v.toString();
            if ("true".equalsIgnoreCase(s)) return "b:true";
            if ("false".equalsIgnoreCase(s)) return "b:false";
            if ("1".equals(s)) return "b:true";
            if ("0".equals(s)) return "b:false";
            return null;
        }
    }
}
