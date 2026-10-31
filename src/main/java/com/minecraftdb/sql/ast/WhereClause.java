package com.minecraftdb.sql.ast;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * WHERE/HAVING 条件树：AND/OR 优先级（NOT &gt; AND &gt; OR，与标准 SQL 一致）、
 * 括号、比较运算符、IS [NOT] NULL、[NOT] IN (...)、[NOT] BETWEEN、LIKE（% 任意串、_ 单字符）。
 *
 * <p>条件两侧操作数为 {@link Expression}（列引用、标量函数、算术、字面量）。
 * HAVING 复用同一棵树：列名位置代之以输出列标签（别名或聚合规范化名），
 * 经由 {@link Resolver} 求值。
 *
 * <p>列名按大小写不敏感解析；引用不存在的列时该条件恒为 false。
 */
public class WhereClause {

    private final Node root;

    public WhereClause(Node root) {
        this.root = root;
    }

    public Node getRoot() {
        return root;
    }

    /** 行求值入口：columnNames/rowValues 等长对齐，列名大小写不敏感 */
    public boolean evaluate(List<String> columnNames, List<Object> rowValues) {
        return evaluate(resolverOf(columnNames, rowValues));
    }

    /** 通用求值入口（HAVING 按输出列、JOIN 等场景使用） */
    public boolean evaluate(Expression.Resolver resolver) {
        return root.eval(new Ctx(resolver));
    }

    public static Expression.Resolver resolverOf(List<String> columnNames, List<Object> rowValues) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < columnNames.size(); i++) {
            idx.putIfAbsent(columnNames.get(i).toLowerCase(Locale.ROOT), i);
        }
        return new Expression.Resolver() {
            @Override
            public Object value(String name) {
                Integer i = idx.get(name.toLowerCase(Locale.ROOT));
                return i != null && i < rowValues.size() ? rowValues.get(i) : null;
            }

            @Override
            public boolean exists(String name) {
                return idx.containsKey(name.toLowerCase(Locale.ROOT));
            }
        };
    }

    private static final class Ctx implements Expression.Resolver {
        final Expression.Resolver delegate;

        Ctx(Expression.Resolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object value(String name) {
            return delegate.value(name);
        }

        @Override
        public boolean exists(String name) {
            return delegate.exists(name);
        }
    }

    // === 树节点 ===

    public abstract static class Node {
        abstract boolean eval(Ctx ctx);
    }

    public static final class AndNode extends Node {
        private final Node left, right;

        public AndNode(Node left, Node right) {
            this.left = left;
            this.right = right;
        }

        @Override
        boolean eval(Ctx ctx) {
            return left.eval(ctx) && right.eval(ctx);
        }
    }

    public static final class OrNode extends Node {
        private final Node left, right;

        public OrNode(Node left, Node right) {
            this.left = left;
            this.right = right;
        }

        @Override
        boolean eval(Ctx ctx) {
            return left.eval(ctx) || right.eval(ctx);
        }
    }

    public static final class NotNode extends Node {
        private final Node inner;

        public NotNode(Node inner) {
            this.inner = inner;
        }

        @Override
        boolean eval(Ctx ctx) {
            return !inner.eval(ctx);
        }
    }

    /** 单操作数条件基类；操作数为 null 时条件恒为 false（unknown 语义） */
    public abstract static class Leaf extends Node {
        final Expression operand;

        Leaf(Expression operand) {
            this.operand = operand;
        }

        /** 条件操作数（供执行器校验聚合是否出现在 WHERE/SET 等场景） */
        public Expression getOperand() {
            return operand;
        }

        Object actualOrNull(Ctx ctx) {
            return operand.eval(ctx);
        }
    }

    /** 收集条件树中引用的全部聚合调用（按规范化名去重），供 HAVING 自动补算使用 */
    public void collectAggregateNames(java.util.Collection<String> out) {
        collectAggregates(root, out);
    }

    private static void collectAggregates(Node node, java.util.Collection<String> out) {
        if (node instanceof Leaf leaf) {
            Expression.collectAggregates(leaf.operand, out);
        } else if (node instanceof NotNode n) {
            collectAggregates(n.inner, out);
        } else if (node instanceof AndNode a) {
            collectAggregates(a.left, out);
            collectAggregates(a.right, out);
        } else if (node instanceof OrNode o) {
            collectAggregates(o.left, out);
            collectAggregates(o.right, out);
        }
    }

    /** expr op value（= / != / <> / > / < / >= / <=），value 亦可为表达式 */
    public static final class OpCondition extends Leaf {
        private final String operator;
        private final Expression value;

        public OpCondition(Expression operand, String operator, Expression value) {
            super(operand);
            this.operator = operator;
            this.value = value;
        }

        @Override
        boolean eval(Ctx ctx) {
            Object actual = actualOrNull(ctx);
            if (actual == null) return false;
            Object expected = value.eval(ctx);
            if (expected == null) return false;
            return switch (operator) {
                case "=" -> compareValues(actual, expected) == 0;
                case "!=", "<>" -> compareValues(actual, expected) != 0;
                case ">" -> compareValues(actual, expected) > 0;
                case "<" -> compareValues(actual, expected) < 0;
                case ">=" -> compareValues(actual, expected) >= 0;
                case "<=" -> compareValues(actual, expected) <= 0;
                default -> false;
            };
        }
    }

    /** expr [NOT] IN (v1, v2, ...)；操作数为 NULL 时恒 false（与 SQL 的 unknown 语义一致） */
    public static final class InCondition extends Leaf {
        private final List<String> values; // 原始文本（字符串可能带引号）
        private final boolean negated;

        public InCondition(Expression operand, List<String> values, boolean negated) {
            super(operand);
            this.values = values;
            this.negated = negated;
        }

        @Override
        boolean eval(Ctx ctx) {
            Object actual = actualOrNull(ctx);
            if (actual == null) return false;
            String actualStr = actual.toString();
            boolean matched = false;
            for (String v : values) {
                if (actualStr.equals(unquote(v))) {
                    matched = true;
                    break;
                }
            }
            return negated ? !matched : matched;
        }
    }

    /** expr [NOT] LIKE pattern（% 任意长度、_ 单个字符） */
    public static final class LikeCondition extends Leaf {
        private final String pattern;
        private final boolean negated;
        private Pattern compiled;

        public LikeCondition(Expression operand, String pattern) {
            this(operand, pattern, false);
        }

        public LikeCondition(Expression operand, String pattern, boolean negated) {
            super(operand);
            this.pattern = pattern;
            this.negated = negated;
        }

        @Override
        boolean eval(Ctx ctx) {
            Object actual = actualOrNull(ctx);
            if (actual == null) return false;
            if (compiled == null) {
                compiled = likeToRegex(unquote(pattern));
            }
            boolean matched = compiled.matcher(actual.toString()).matches();
            return negated ? !matched : matched;
        }

        private static Pattern likeToRegex(String pattern) {
            StringBuilder sb = new StringBuilder();
            for (char c : pattern.toCharArray()) {
                switch (c) {
                    case '%' -> sb.append(".*");
                    case '_' -> sb.append('.');
                    case '\\' -> sb.append("\\\\");
                    default -> {
                        if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) sb.append('\\');
                        sb.append(c);
                    }
                }
            }
            return Pattern.compile(sb.toString(), Pattern.DOTALL);
        }
    }

    /** expr [NOT] BETWEEN low AND high（双闭区间） */
    public static final class BetweenCondition extends Leaf {
        private final String low;  // 原始文本
        private final String high; // 原始文本
        private final boolean negated;

        public BetweenCondition(Expression operand, String low, String high, boolean negated) {
            super(operand);
            this.low = low;
            this.high = high;
            this.negated = negated;
        }

        @Override
        boolean eval(Ctx ctx) {
            Object actual = actualOrNull(ctx);
            if (actual == null) return false;
            Object lo = valueOf(low);
            Object hi = valueOf(high);
            if (lo == null || hi == null) return false;
            boolean in = compareValues(actual, lo) >= 0 && compareValues(actual, hi) <= 0;
            return negated ? !in : in;
        }

        private static Object valueOf(String raw) {
            String v = unquote(raw);
            if (v == null) return null;
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException e) {
                try {
                    return Double.parseDouble(v);
                } catch (NumberFormatException e2) {
                    return v;
                }
            }
        }
    }

    /** expr IS [NOT] NULL；引用不存在的列时恒为 false（与其他条件一致） */
    public static final class NullCondition extends Leaf {
        private final boolean negated;

        public NullCondition(Expression operand, boolean negated) {
            super(operand);
            this.negated = negated;
        }

        @Override
        boolean eval(Ctx ctx) {
            if (!operand.exists(ctx)) return false;
            boolean isNull = operand.eval(ctx) == null;
            return negated ? !isNull : isNull;
        }
    }

    // === 求值工具 ===

    /** null 与 null 相等；数值按数值比较；其余按字符串比较 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compareValues(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        try {
            if (a instanceof Comparable && a.getClass().isInstance(b)) {
                return ((Comparable) a).compareTo(b);
            }
        } catch (Exception ignored) {}
        return a.toString().compareTo(b.toString());
    }

    static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
