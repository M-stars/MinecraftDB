package com.minecraftdb.database;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.minecraftdb.sql.ast.ColumnDefinition;
import com.minecraftdb.storage.CoordinateAllocator;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理表结构（Schema）。
 * 所有元数据存储在 config/minecraftdb/schemas/<世界名>/ 目录下：
 *   - {表名}.json 存储Schema定义
 *   - _tables.json 存储表名到坐标的映射注册表
 *   - _indexes.json 存储索引元数据（当前索引仅作为元数据，不参与查询计划）
 */
public class SchemaManager {

    private static final Logger LOGGER = LogManager.getLogger("MinecraftDB");
    private static final String INDEX_FILE = "_indexes.json";

    private final ServerLevel level;
    private final CoordinateAllocator coordAllocator;
    private final Gson gson = new Gson();
    private final Path schemaDir;
    private final Map<String, List<ColumnDefinition>> schemaCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> indexCache = new ConcurrentHashMap<>();

    public SchemaManager(ServerLevel level, CoordinateAllocator coordAllocator) {
        this.level = level;
        this.coordAllocator = coordAllocator;
        this.schemaDir = Paths.get("config", "minecraftdb", "schemas", extractWorldFolderName());
        try {
            Files.createDirectories(schemaDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建schema目录: " + schemaDir, e);
        }
    }

    private String extractWorldFolderName() {
        return level.dimension().location().getPath();
    }

    // === Schema 读写 ===

    public void saveSchema(String tableName, List<ColumnDefinition> schema) {
        Path schemaFile = schemaDir.resolve(tableName + ".json");
        try {
            String json = gson.toJson(schema);
            Files.writeString(schemaFile, json);
        } catch (IOException e) {
            throw new RuntimeException("保存schema失败: " + tableName, e);
        }
        schemaCache.put(tableName, schema);
    }

    public List<ColumnDefinition> getSchema(String tableName) {
        List<ColumnDefinition> cached = schemaCache.get(tableName);
        if (cached != null) return cached;

        Path schemaFile = schemaDir.resolve(tableName + ".json");
        if (!Files.exists(schemaFile)) return null;

        try {
            String json = Files.readString(schemaFile);
            List<ColumnDefinition> schema = gson.fromJson(json,
                    new TypeToken<List<ColumnDefinition>>() {}.getType());
            if (schema != null) {
                schemaCache.put(tableName, schema);
            }
            return schema;
        } catch (IOException e) {
            return null;
        }
    }

    public void removeSchema(String tableName) {
        schemaCache.remove(tableName);
        if (indexCache.remove(tableName) != null) {
            saveIndexes();
        }
        try {
            Files.deleteIfExists(schemaDir.resolve(tableName + ".json"));
        } catch (IOException ignored) {}
    }

    // === 坐标注册表（替代告示牌，用于重启后恢复） ===

    public void saveTableRegistry(Map<String, CoordinateAllocator.TableCoords> coordsMap) {
        Map<String, RegistryEntry> registry = new LinkedHashMap<>();
        for (var entry : coordsMap.entrySet()) {
            var tc = entry.getValue();
            registry.put(entry.getKey(), new RegistryEntry(tc.xStart, tc.xEnd, tc.nextZ));
        }
        try {
            String json = gson.toJson(registry);
            Files.writeString(schemaDir.resolve("_tables.json"), json);
        } catch (IOException ignored) {}
    }

    public Map<String, CoordinateAllocator.TableCoords> loadTableRegistry() {
        Path registryFile = schemaDir.resolve("_tables.json");
        if (!Files.exists(registryFile)) return Collections.emptyMap();

        try {
            String json = Files.readString(registryFile);
            Map<String, RegistryEntry> registry = gson.fromJson(json,
                    new TypeToken<Map<String, RegistryEntry>>() {}.getType());
            if (registry == null) return Collections.emptyMap();

            Map<String, CoordinateAllocator.TableCoords> result = new LinkedHashMap<>();
            for (var entry : registry.entrySet()) {
                RegistryEntry re = entry.getValue();
                result.put(entry.getKey(),
                        new CoordinateAllocator.TableCoords(entry.getKey(), re.xStart, re.xEnd, re.nextZ));
            }
            return result;
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }

    // === 索引 ===

    public void addIndex(String tableName, String indexName, String columnName) {
        Map<String, String> indexes = indexCache.computeIfAbsent(tableName, k -> new LinkedHashMap<>());
        indexes.put(indexName, columnName);
        saveIndexes();
    }

    public Map<String, String> getIndexes(String tableName) {
        return indexCache.getOrDefault(tableName, Collections.emptyMap());
    }

    /** 索引元数据持久化到 _indexes.json（不参与查询计划，仅供 SHOW INDEXES 与未来优化器使用） */
    private void saveIndexes() {
        try {
            Files.writeString(schemaDir.resolve(INDEX_FILE), gson.toJson(indexCache));
        } catch (IOException e) {
            LOGGER.error("保存索引元数据失败: {}", e.toString());
        }
    }

    private void loadIndexes() {
        Path file = schemaDir.resolve(INDEX_FILE);
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file);
            Map<String, Map<String, String>> data = gson.fromJson(json,
                    new TypeToken<Map<String, Map<String, String>>>() {}.getType());
            if (data != null) {
                indexCache.putAll(data);
            }
        } catch (IOException e) {
            LOGGER.error("加载索引元数据失败: {}", e.toString());
        }
    }

    // === 启动时加载 ===

    public void loadAllSchemas() {
        loadIndexes();
        try {
            if (Files.exists(schemaDir)) {
                try (var files = Files.list(schemaDir)) {
                    files.filter(f -> f.toString().endsWith(".json") && !f.getFileName().toString().startsWith("_"))
                         .forEach(f -> {
                             String tableName = f.getFileName().toString().replace(".json", "");
                             getSchema(tableName);
                         });
                }
            }
        } catch (IOException ignored) {}
    }

    /**
     * 注册表条目，用于 JSON 序列化
     */
    private static class RegistryEntry {
        int xStart;
        int xEnd;
        long nextZ;

        RegistryEntry(int xStart, int xEnd, long nextZ) {
            this.xStart = xStart;
            this.xEnd = xEnd;
            this.nextZ = nextZ;
        }
    }
}
