package com.minecraftdb.sql.ast;

import java.util.ArrayList;
import java.util.List;

public class ColumnDefinition {
    public final String name;
    public final String dataType;
    public final List<String> constraints;
    /** DEFAULT 值的原始 token（字符串可能带引号）；null 表示未定义 */
    public String defaultValue;
    /** 自增列：INSERT 未提供时取现有最大值 +1，并强制唯一 */
    public boolean autoIncrement;
    /** 外键：REFERENCES 的父表名；null 表示无外键 */
    public String referencesTable;
    /** 外键：父表列名；null 表示父表主键列 */
    public String referencesColumn;

    public boolean isForeignKey() {
        return referencesTable != null;
    }

    public boolean isUnique() {
        return hasConstraint("UNIQUE");
    }

    public ColumnDefinition(String name, String dataType) {
        this.name = name;
        this.dataType = dataType.toUpperCase();
        this.constraints = new ArrayList<>();
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public void setAutoIncrement(boolean autoIncrement) {
        this.autoIncrement = autoIncrement;
        if (autoIncrement && !constraints.contains("AUTO_INCREMENT")) {
            constraints.add("AUTO_INCREMENT");
        }
    }

    public void addConstraint(String constraint) {
        this.constraints.add(constraint.toUpperCase());
    }

    public boolean hasConstraint(String constraint) {
        return constraints.contains(constraint.toUpperCase());
    }

    public boolean isNullable() {
        return !hasConstraint("NOT NULL");
    }

    public boolean isPrimaryKey() {
        return hasConstraint("PRIMARY KEY");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name).append(" ").append(dataType);
        for (String c : constraints) {
            sb.append(" ").append(c);
        }
        if (defaultValue != null) {
            sb.append(" DEFAULT ").append(defaultValue);
        }
        return sb.toString();
    }
}
