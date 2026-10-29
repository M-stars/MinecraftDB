package com.minecraftdb.storage;

import com.minecraftdb.config.ModConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages coordinate allocation for tables in the database world.
 * Each table gets a contiguous X-axis range; rows use Z-axis; columns use Y-axis.
 */
public class CoordinateAllocator {

    public static final int DEFAULT_TABLE_WIDTH = 100;
    public static final int METADATA_Y = 255;
    public static final int DATA_START_Y = 1;
    public static final int METADATA_Z = 0;

    private final Map<String, TableCoords> tableCoords = new ConcurrentHashMap<>();
    private int nextXStart = 0;

    // LRU cache for recently accessed coordinate lookups
    private final Map<String, TableCoords> coordCache;

    public CoordinateAllocator() {
        int cacheSize = ModConfig.DATABASE_CACHE_SIZE;
        this.coordCache = new LinkedHashMap<>(cacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TableCoords> eldest) {
                return size() > cacheSize;
            }
        };
    }

    public synchronized TableCoords allocate(String tableName, int columnCount) {
        if (tableCoords.containsKey(tableName)) {
            return tableCoords.get(tableName);
        }

        int width = Math.max(DEFAULT_TABLE_WIDTH, columnCount * 2);
        // 行号从 1 开始：readAllRows 按 z=1..nextZ-1 读取，z=0 是表边界/元数据标记区
        TableCoords coords = new TableCoords(tableName, nextXStart, nextXStart + width - 1, 1);
        tableCoords.put(tableName, coords);
        nextXStart += width + 10; // 10-block gap between tables
        coordCache.put(tableName, coords);
        return coords;
    }

    public synchronized void expandTable(String tableName, int additionalWidth) {
        TableCoords coords = tableCoords.get(tableName);
        if (coords == null) return;

        // Shift all subsequent tables
        int shift = additionalWidth + 10;
        for (TableCoords tc : tableCoords.values()) {
            if (tc.xStart > coords.xEnd) {
                tc.xStart += shift;
                tc.xEnd += shift;
            }
        }
        coords.xEnd += additionalWidth;
        nextXStart += shift;
    }

    public TableCoords getCoords(String tableName) {
        TableCoords cached = coordCache.get(tableName);
        if (cached != null) return cached;

        TableCoords coords = tableCoords.get(tableName);
        if (coords != null) {
            coordCache.put(tableName, coords);
        }
        return coords;
    }

    public synchronized void remove(String tableName) {
        // 仅解除注册：不平移其他表坐标、不回退 nextXStart。
        // 世界里的羊毛块不会移动，若平移坐标，被删表之后的所有表都会指向错误区域（数据丢失/错位）。
        // 被删表占用的 X 区间保留为空洞，新表继续向后分配。
        tableCoords.remove(tableName);
        coordCache.remove(tableName);
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

        public int getWidth() {
            return xEnd - xStart + 1;
        }

        @Override
        public String toString() {
            return String.format("Table[%s] X:[%d-%d] nextZ:%d", tableName, xStart, xEnd, nextZ);
        }
    }
}
