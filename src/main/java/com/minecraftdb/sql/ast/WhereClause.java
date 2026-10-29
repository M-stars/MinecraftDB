package com.minecraftdb.sql.ast;

import java.util.ArrayList;
import java.util.List;

public class WhereClause {
    public final List<Condition> conditions = new ArrayList<>();
    public final List<LogicalOp> logicalOps = new ArrayList<>();

    /** 首次求值时把条件列名解析为列下标（大小写不敏感），之后每行 O(1) 定位 */
    private int[] resolvedColumnIdx;

    public enum LogicalOp {
        AND, OR
    }

    public static class Condition {
        public final String column;
        public final String operator;
        public final String value;

        public Condition(String column, String operator, String value) {
            this.column = column;
            this.operator = operator;
            this.value = value;
        }

        public boolean evaluate(Object actualValue) {
            if (actualValue == null) {
                return "IS NULL".equalsIgnoreCase(operator) || "=".equals(operator) && value == null;
            }

            String actualStr = actualValue.toString();

            return switch (operator) {
                case "=" -> actualStr.equals(unquote(value));
                case "!=", "<>" -> !actualStr.equals(unquote(value));
                case ">" -> compareValues(actualValue, unquote(value)) > 0;
                case "<" -> compareValues(actualValue, unquote(value)) < 0;
                case ">=" -> compareValues(actualValue, unquote(value)) >= 0;
                case "<=" -> compareValues(actualValue, unquote(value)) <= 0;
                default -> false;
            };
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private int compareValues(Object a, String b) {
            try {
                if (a instanceof Number) {
                    double da = ((Number) a).doubleValue();
                    double db = Double.parseDouble(b);
                    return Double.compare(da, db);
                }
                if (a instanceof Comparable) {
                    return ((Comparable) a).compareTo(b);
                }
                return a.toString().compareTo(b);
            } catch (Exception e) {
                return 0;
            }
        }

        private static String unquote(String s) {
            if (s == null) return null;
            if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
                return s.substring(1, s.length() - 1);
            }
            return s;
        }
    }

    public void addCondition(Condition cond) {
        conditions.add(cond);
    }

    public void addLogicalOp(LogicalOp op) {
        logicalOps.add(op);
    }

    public boolean evaluate(List<String> columnNames, List<Object> rowValues) {
        if (conditions.isEmpty()) return true;

        if (resolvedColumnIdx == null) {
            resolveColumns(columnNames);
        }

        boolean result = evaluateCondition(0, columnNames, rowValues);
        for (int i = 0; i < logicalOps.size(); i++) {
            boolean next = evaluateCondition(i + 1, columnNames, rowValues);
            if (logicalOps.get(i) == LogicalOp.AND) {
                result = result && next;
            } else {
                result = result || next;
            }
        }
        return result;
    }

    private void resolveColumns(List<String> columnNames) {
        resolvedColumnIdx = new int[conditions.size()];
        for (int i = 0; i < conditions.size(); i++) {
            resolvedColumnIdx[i] = indexOfIgnoreCase(columnNames, conditions.get(i).column);
        }
    }

    private static int indexOfIgnoreCase(List<String> names, String target) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(target)) return i;
        }
        return -1;
    }

    private boolean evaluateCondition(int idx, List<String> columnNames, List<Object> rowValues) {
        if (idx >= conditions.size()) return true;
        Condition cond = conditions.get(idx);
        int colIdx = resolvedColumnIdx != null
                ? resolvedColumnIdx[idx]
                : indexOfIgnoreCase(columnNames, cond.column);
        if (colIdx < 0 || colIdx >= rowValues.size()) return false;
        return cond.evaluate(rowValues.get(colIdx));
    }
}
