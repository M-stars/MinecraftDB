package com.minecraftdb.sql.ast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 表达式 AST：字面量、列引用、算术运算（+ - * / %）、函数调用。
 *
 * <p>供 UPDATE SET 右值、WHERE/HAVING 条件操作数与 SELECT 投影项共用。
 * 聚合函数（COUNT/SUM/AVG/MIN/MAX）也是 Func 节点，但求值时不在此计算——
 * 执行器按组预计算后，经由 {@link Resolver#value(String)} 以规范化名
 * （如 {@code COUNT(*)}）查得，见 {@link Func#eval}。
 *
 * <p>所有求值为纯 Java，可脱离 Minecraft 环境直接单测。
 */
public abstract class Expression {

    /** 聚合函数名集合（大写） */
    public static final Set<String> AGGREGATE_FUNCTIONS =
            Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");

    /** 标量函数名集合（大写） */
    public static final Set<String> SCALAR_FUNCTIONS =
            Set.of("UPPER", "UCASE", "LOWER", "LCASE", "LENGTH", "ABS", "ROUND", "COALESCE", "TRIM");

    /** 列值解析器：按名称取行内值（实现方负责大小写不敏感与缺列返回 null） */
    public interface Resolver {
        Object value(String name);

        /** 名称是否可解析（区分「列不存在」与「列存在但值为 NULL」，IS NULL 语义依赖它） */
        default boolean exists(String name) {
            return value(name) != null;
        }
    }

    /** 求值。聚合节点通过 resolver 查预计算结果；列不存在时返回 null */
    public abstract Object eval(Resolver resolver);

    /** 名称是否可解析：列存在 / 聚合已预计算 / 字面量恒真；标量函数要求全部参数可解析 */
    public abstract boolean exists(Resolver resolver);

    /** 还原为 SQL 文本（同时作为 SELECT 项的默认输出列名） */
    public abstract String sqlText();

    @Override
    public String toString() {
        return sqlText();
    }

    /** 是否为聚合函数调用 */
    public static boolean isAggregate(Expression e) {
        return e instanceof Func f && AGGREGATE_FUNCTIONS.contains(f.name);
    }

    /** 规范化文本：Func → NAME(arg,...)，ColumnRef → 原样列名，Literal → 原始文本 */
    public static String canonical(Expression e) {
        return e.sqlText();
    }

    /** 收集表达式中出现的全部聚合调用（按规范化名去重） */
    public static void collectAggregates(Expression e, Collection<String> out) {
        if (e instanceof Func f) {
            if (AGGREGATE_FUNCTIONS.contains(f.name)) {
                out.add(f.sqlText());
                return; // 聚合参数内部不再下钻
            }
            for (Expression a : f.args) collectAggregates(a, out);
        } else if (e instanceof Arith a) {
            collectAggregates(a.left, out);
            collectAggregates(a.right, out);
        }
    }

    /** 收集表达式中未被聚合包裹的裸列引用（用于 GROUP BY 校验） */
    public static void collectBareColumns(Expression e, Collection<String> out) {
        if (e instanceof ColumnRef c) {
            out.add(c.name);
        } else if (e instanceof Arith a) {
            collectBareColumns(a.left, out);
            collectBareColumns(a.right, out);
        } else if (e instanceof Func f && !AGGREGATE_FUNCTIONS.contains(f.name)) {
            for (Expression a : f.args) collectBareColumns(a, out);
        }
    }

    // === 节点 ===

    /**
     * 字面量。typed 为去引号后的值：String / Long / Double / Boolean / null，
     * 以及 SELECT * 与 COUNT(*) 中的 "*"；raw 保留原始文本（含引号），供
     * INSERT/SET 按列类型解析。
     */
    public static final class Literal extends Expression {
        public final Object typed;
        public final String raw;

        public Literal(Object typed, String raw) {
            this.typed = typed;
            this.raw = raw;
        }

        @Override
        public Object eval(Resolver resolver) {
            return typed;
        }

        @Override
        public boolean exists(Resolver resolver) {
            return true;
        }

        @Override
        public String sqlText() {
            return raw;
        }
    }

    public static final class ColumnRef extends Expression {
        public final String name;

        public ColumnRef(String name) {
            this.name = name;
        }

        @Override
        public Object eval(Resolver resolver) {
            return resolver.value(name);
        }

        @Override
        public boolean exists(Resolver resolver) {
            return resolver.exists(name);
        }

        @Override
        public String sqlText() {
            return name;
        }
    }

    /** 算术运算：+ - * / %。操作数为 null 或除零时结果为 null */
    public static final class Arith extends Expression {
        public final String op;
        public final Expression left;
        public final Expression right;

        public Arith(String op, Expression left, Expression right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        public Object eval(Resolver resolver) {
            Object a = left.eval(resolver);
            Object b = right.eval(resolver);
            if (a == null || b == null) return null;
            Double x = toDouble(a);
            Double y = toDouble(b);
            if (x == null || y == null) return null;
            return switch (op) {
                case "+" -> x + y;
                case "-" -> x - y;
                case "*" -> x * y;
                case "/" -> y == 0 ? null : x / y;
                case "%" -> y == 0 ? null : x % y;
                default -> null;
            };
        }

        @Override
        public boolean exists(Resolver resolver) {
            return left.exists(resolver) && right.exists(resolver);
        }

        @Override
        public String sqlText() {
            return left.sqlText() + " " + op + " " + right.sqlText();
        }
    }

    /** 函数调用：聚合（经 resolver 查预计算值）或标量（本地求值） */
    public static final class Func extends Expression {
        public final String name; // 大写
        public final List<Expression> args;

        public Func(String name, List<Expression> args) {
            this.name = name.toUpperCase(Locale.ROOT);
            this.args = args;
        }

        @Override
        public Object eval(Resolver resolver) {
            if (AGGREGATE_FUNCTIONS.contains(name)) {
                return resolver.value(sqlText());
            }
            List<Object> vals = new ArrayList<>(args.size());
            for (Expression a : args) vals.add(a.eval(resolver));
            return evalScalar(name, vals);
        }

        @Override
        public boolean exists(Resolver resolver) {
            if (AGGREGATE_FUNCTIONS.contains(name)) {
                return resolver.exists(sqlText());
            }
            for (Expression a : args) {
                if (!a.exists(resolver)) return false;
            }
            return true;
        }

        @Override
        public String sqlText() {
            StringBuilder sb = new StringBuilder(name).append('(');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(args.get(i).sqlText());
            }
            return sb.append(')').toString();
        }
    }

    // === 标量函数求值（纯函数，可直接单测） ===

    public static Object evalScalar(String name, List<Object> args) {
        return switch (name) {
            case "UPPER", "UCASE" -> {
                Object v = first(args);
                yield v == null ? null : v.toString().toUpperCase(Locale.ROOT);
            }
            case "LOWER", "LCASE" -> {
                Object v = first(args);
                yield v == null ? null : v.toString().toLowerCase(Locale.ROOT);
            }
            case "LENGTH" -> {
                Object v = first(args);
                yield v == null ? null : (long) v.toString().length();
            }
            case "TRIM" -> {
                Object v = first(args);
                yield v == null ? null : v.toString().trim();
            }
            case "ABS" -> {
                Object v = first(args);
                if (v == null) yield null;
                Double d = toDouble(v);
                yield d == null ? null : Math.abs(d);
            }
            case "ROUND" -> {
                Object v = first(args);
                if (v == null) yield null;
                Double d = toDouble(v);
                if (d == null) yield null;
                Integer scale = 0;
                if (args.size() > 1) {
                    Double s = toDouble(args.get(1));
                    if (s != null) scale = (int) Math.round(s);
                }
                double factor = Math.pow(10, scale);
                double rounded = Math.round(d * factor) / factor;
                yield scale <= 0 ? (Object) (long) rounded : rounded;
            }
            case "COALESCE" -> {
                Object result = null;
                for (Object a : args) {
                    if (a != null) {
                        result = a;
                        break;
                    }
                }
                yield result;
            }
            default -> null;
        };
    }

    private static Object first(List<Object> args) {
        return args.isEmpty() ? null : args.get(0);
    }

    private static Double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
