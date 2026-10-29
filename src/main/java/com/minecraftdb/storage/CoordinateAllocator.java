package com.minecraftdb.storage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages coordinate allocation for tables in the database world.
 * Each table gets a contiguous X-axis range; rows use Z-axis; columns use Y-axis.
 */
public class CoordinateAllocator {

    public static final int DEFAULT_TABLE_WIDTH = 100;

    private final Map<String, TableCoords> tableCoords = new ConcurrentHashMap<>();
    private int nextXStart = 0;

    public CoordinateAllocator() {
    }

    public synchronized TableCoords allocate(String tableName, int columnCount) {
        if (tableCoords.containsKey(tableName)) {
            return tableCoords.get(tableName);
        }

        int width = Math.max(DEFAULT_TABLE_WIDTH, columnCount * 2);
        // 行号从 1 开始： getMaxZ 流式遍历 z=1..nextZ-1，z=0 是表边界/元数据标记区
        TableCoords coords = new TableCoords(tableName, nextXStart, nextXStart + width - 1, 1);
        tableCoords.put(tableName, coords);
        nextXStart += width + 10; // 10-block gap between tables
        return coords;
    }

    public TableCoords getCoords(String tableName) {
        return tableCoords.get(tableName);
    }

    public synchronized void remove(String tableName) {
        // 仅解除注册：不平移其他表坐标、不回退 nextXStart。
        // 世界里的羊毛块不会移动，若平移坐标，被删表之后的所有表都会指向错误区域（数据丢失/错位）。
        // 被删表占用的 X 区间保留为空洞，新表继续向后分配。
        tableCoords.remove(tableName);
    }

    public Set<String> getTableNames() {
        return Collections.unmodifiableSet(tableCoords.keySet());
    }

    public Map<String, TableCoords> getAllCoords() {
        return Collections.unmodifiableMap(tableCoords);
    }

    public synchronized void loadFromExisting(Map<String, TableCoords> existing) {
        tableCoords.putAll(existing);
        for (TableCoords tc : existing.values()) {
            if (tc.nextZ < 1) tc.nextZ = 1; // 旧注册表 nextZ 可能为 0，行号必须从 1 起
            if (tc.xEnd >= nextXStart) {
                nextXStart = tc.xEnd + 11;
            }
        }
    }

    /**
     * Represents the coordinate range for a single table.
     */
    public static class TableCoords {
        public final String tableName;
        public int xStart;
        public int xEnd;
        public long nextZ; // next available row Z coordinate

        public TableCoords(String tableName, int xStart, int xEnd, long nextZ) {
            this.tableName = tableName;
            this.xStart = xStart;
            this.xEnd = xEnd;
            this.nextZ = nextZ;
        }

        @Override
        public String toString() {
            return String.format("Table[%s] X:[%d-%d] nextZ:%d", tableName, xStart, xEnd, nextZ);
        }
    }
}
