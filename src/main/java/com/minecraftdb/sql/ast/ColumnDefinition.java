package com.minecraftdb.sql.ast;

import java.util.ArrayList;
import java.util.List;

public class ColumnDefinition {
    public final String name;
    public final String dataType;
    public final List<String> constraints;

    public ColumnDefinition(String name, String dataType) {
        this.name = name;
        this.dataType = dataType.toUpperCase();
        this.constraints = new ArrayList<>();
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
        return sb.toString();
    }
}
