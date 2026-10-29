package com.minecraftdb.sql.ast;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * WHERE 条件树：AND/OR 优先级（NOT &gt; AND &gt; OR，与标准 SQL 一致）、
 * 括号、比较运算符、IS [NOT] NULL、[NOT] IN (...)、LIKE（% 任意串、_ 单字符）。
 *
 * <p>列名按大小写不敏感解析；引用不存在的列时该条件恒为 false。
 */
public class WhereClause {

    private final Node root;
    /** 首次求值时构建：小写列名 → 下标 */
    private Map<String, Integer> columnIndex;

    public WhereClause(Node root) {
        this.root = root;
    }

    public Node getRoot() {
        return root;
    }

    public boolean evaluate(List<String> columnNames, List<Object> rowValues) {
        if (columnIndex == null) {
            columnIndex = new HashMap<>();
            for (int i = 0; i < columnNames.size(); i++) {
                columnIndex.putIfAbsent(columnNames.get(i).toLowerCase(Locale.ROOT), i);
            }
        }
        return root.eval(new Ctx(rowValues));
    }

    private final class Ctx {
        final List<Object> rowValues;

        Ctx(List<Object> rowValues) {
            this.rowValues = rowValues;
        }

        int indexOf(String column) {
            Integer idx = columnIndex.get(column.toLowerCase(Locale.ROOT));
            return idx != null ? idx : -1;
        }

        Object value(int idx) {
            return idx >= 0 && idx < rowValues.size() ? rowValues.get(idx) : null;
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

    /** 叶子基类：引用一列；列不存在时条件恒为 false */
    public abstract static class Leaf extends Node {
        final String column;

        Leaf(String column) {
            this.column = column;
        }

        Object actualOrNull(Ctx ctx) {
            int idx = ctx.indexOf(column);
            return idx < 0 ? null : ctx.value(idx);
        }
    }

    /** col op value（= / != / <> / > / < / >= / <=） */
    public static final class OpCondition extends Leaf {
        private final String operator;
        private final String value;

        public OpCondition(String column, String operator, String value) {
            super(column);
            this.operator = operator;
            this.value = value;
        }

        @Override
        boolean eval(Ctx ctx) {
            Object actual = actualOrNull(ctx);
            if (actual == null) return false;
            String actualStr = actual.toString();
            return switch (operator) {
                case "=" -> actualStr.equals(unquote(value));
                case "!=", "<>" -> !actualStr.equals(unquote(value));
                case ">" -> compareValues(actual, unquote(value)) > 0;
                case "<" -> compareValues(actual, unquote(value)) < 0;
                case ">=" -> compareValues(actual, unquote(value)) >= 0;
                case "<=" -> compareValues(actual, unquote(value)) <= 0;
                default -> false;
            };
        }
    }

    /** col [NOT] IN (v1, v2, ...)；值为 NULL 时恒 false（与 SQL 的 unknown 语义一致） */
    public static final class InCondition extends Leaf {
        private final List<String> values;
        private final boolean negated;

        public InCondition(String column, List<String> values, boolean negated) {
            super(column);
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

    /** col [NOT] LIKE pattern（% 任意长度、_ 单个字符） */
    public static final class LikeCondition extends Leaf {
        private final String pattern;
        private final boolean negated;
        private Pattern compiled;

        public LikeCondition(String column, String pattern) {
            this(column, pattern, false);
        }

        public LikeCondition(String column, String pattern, boolean negated) {
            super(column);
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

    /** col IS [NOT] NULL */
    public static final class NullCondition extends Leaf {
        private final boolean negated;

        public NullCondition(String column, boolean negated) {
            super(column);
            this.negated = negated;
        }

        @Override
        boolean eval(Ctx ctx) {
            int idx = ctx.indexOf(column);
            if (idx < 0) return false;
            boolean isNull = ctx.value(idx) == null;
            return negated ? !isNull : isNull;
        }
    }

    // === 求值工具 ===

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compareValues(Object a, String b) {
        try {
            if (a instanceof Number) {
                return Double.compare(((Number) a).doubleValue(), Double.parseDouble(b));
            }
            if (a instanceof Comparable) {
                return ((Comparable) a).compareTo(b);
            }
            return a.toString().compareTo(b);
        } catch (Exception e) {
            return 0;
        }
    }

    static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
