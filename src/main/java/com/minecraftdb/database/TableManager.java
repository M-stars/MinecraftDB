package com.minecraftdb.database;

import com.minecraftdb.sql.ast.ColumnDefinition;
import com.minecraftdb.storage.BlockOperator;
import com.minecraftdb.storage.CoordinateAllocator;
import com.minecraftdb.storage.WoolDecoder;
import com.minecraftdb.storage.WoolEncoder;
import com.minecraftdb.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

/**
 * Manages table data operations: CRUD for rows using wool block encoding.
 */
public class TableManager {

    private final ServerLevel level;
    private final CoordinateAllocator coordAllocator;
    private final BlockOperator blockOp;
    private final DatabaseManager dbManager;

    public TableManager(ServerLevel level, CoordinateAllocator coordAllocator, DatabaseManager dbManager) {
        this.level = level;
        this.coordAllocator = coordAllocator;
        this.blockOp = new BlockOperator(level);
        this.dbManager = dbManager;
    }

    public BlockOperator getBlockOperator() {
        return blockOp;
    }

    // === Table lifecycle ===

    public boolean tableExists(String tableName) {
        return coordAllocator.getCoords(tableName) != null;
    }

    public Set<String> listTables() {
        return coordAllocator.getTableNames();
    }

    public void createTable(String tableName, List<ColumnDefinition> columns) {
        CoordinateAllocator.TableCoords coords = coordAllocator.allocate(tableName, columns.size());

        // Place bedrock markers at table boundaries (Y=0)
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = coords.xStart; x <= coords.xEnd; x++) {
            pos.set(x, 0, 0);
            level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 3);
        }

        // 数据区保持空气：空气 = 列数据结束符，写入数据时再放羊毛
        dbManager.saveTableRegistry();
        blockOp.flush();
    }

    public void dropTable(String tableName) {
        CoordinateAllocator.TableCoords coords = coordAllocator.getCoords(tableName);
        if (coords == null) return;

        // Clear all blocks in the table's area
        WorldUtils.fillAreaAir(level, coords.xStart, coords.xEnd,
                WorldUtils.DATA_START_Y, WorldUtils.DATA_MAX_Y,
                0, (int) Math.min(coords.nextZ, 10000));

        coordAllocator.remove(tableName);
        dbManager.saveTableRegistry();
    }

    // === Row operations ===

    public void insertRow(String tableName, List<ColumnDefinition> schema, Map<String, Object> rowData) {
        CoordinateAllocator.TableCoords coords = coordAllocator.getCoords(tableName);
        if (coords == null) throw new IllegalStateException("Table not found: " + tableName);

        long z = coords.nextZ++;
        int maxLayers = WorldUtils.DATA_MAX_Y - WorldUtils.DATA_START_Y + 1;

        for (int colIdx = 0; colIdx < schema.size(); colIdx++) {
            ColumnDefinition colDef = schema.get(colIdx);
            Object value = rowData.get(colDef.name);
            int x = coords.xStart + colIdx;

            List<DyeColor> colorSeq = encodeValue(value, colDef.dataType);
            int layers = Math.min(colorSeq.size(), maxLayers);
            for (int dy = 0; dy < layers; dy++) {
                blockOp.writeWool(x, WorldUtils.DATA_START_Y + dy, (int) z, colorSeq.get(dy));
            }
            // 该列剩余 Y 层保持空气（列数据结束符）
        }

        blockOp.flush();
    }

    /**
     * 一行数据及其在世界上真实的 Z 坐标。
     * 已删除的行（空行）不进入结果，列表下标 ≠ z 坐标，写回时必须用这里的 z。
     */
    public record RowWithZ(long z, Map<String, Object> values) {}

    public List<RowWithZ> readAllRows(String tableName, List<ColumnDefinition> schema) {
        CoordinateAllocator.TableCoords coords = coordAllocator.getCoords(tableName);
        if (coords == null) return Collections.emptyList();

        List<RowWithZ> rows = new ArrayList<>();
        long maxZ = coords.nextZ;

        for (int z = 1; z < maxZ; z++) {
            Map<String, Object> row = readRow(tableName, schema, z);
            if (row != null && !row.isEmpty()) {
                rows.add(new RowWithZ(z, row));
            }
        }

        return rows;
    }

    public Map<String, Object> readRow(String tableName, List<ColumnDefinition> schema, int z) {
        CoordinateAllocator.TableCoords coords = coordAllocator.getCoords(tableName);
        if (coords == null) return null;

        Map<String, Object> row = new LinkedHashMap<>();
        boolean hasData = false;

        for (int colIdx = 0; colIdx < schema.size(); colIdx++) {
            ColumnDefinition colDef = schema.get(colIdx);
            int x = coords.xStart + colIdx;

            List<DyeColor> colors = new ArrayList<>();
            for (int y = WorldUtils.DATA_START_Y; y <= WorldUtils.DATA_MAX_Y; y++) {
                DyeColor color = blockOp.readWool(x, y, z);
                // 非羊毛方块（空气等）= 列数据结束；白色羊毛是合法数据（数字 0 / nibble 0x0）
                if (color == null) break;
                colors.add(color);
            }

            if (!colors.isEmpty()) hasData = true;

            Object value = decodeValue(colors, colDef.dataType);
            row.put(colDef.name, value);
        }

        return hasData ? row : null;
    }

    public void updateRow(String tableName, List<ColumnDefinition> schema, long z, Map<String, Object> newData) {
        CoordinateAllocator.TableCoords coords = coordAllocator.getCoords(tableName);
        if (coords == null) throw new IllegalStateException("Table not found: " + tableName);

        int maxLayers = WorldUtils.DATA_MAX_Y - WorldUtils.DATA_START_Y + 1;

        for (int colIdx = 0; colIdx < schema.size(); colIdx++) {
            ColumnDefinition colDef = schema.get(colIdx);
            Object value = newData.get(colDef.name);
            int x = coords.xStart + colIdx;

            // 整列先清为空气再写新数据，避免新数据比旧数据短时残留上层旧色
            for (int y = WorldUtils.DATA_START_Y; y <= WorldUtils.DATA_MAX_Y; y++) {
                blockOp.clearBlock(x, y, (int) z);
            }

            // Write new data
            List<DyeColor> colorSeq = encodeValue(value, colDef.dataType);
            int layers = Math.min(colorSeq.size(), maxLayers);
            for (int dy = 0; dy < layers; dy++) {
                blockOp.writeWool(x, WorldUtils.DATA_START_Y + dy, (int) z, colorSeq.get(dy));
            }
        }

        blockOp.flush();
    }

    public void deleteRow(String tableName, List<ColumnDefinition> schema, long z) {
        CoordinateAllocator.TableCoords coords = coordAllocator.getCoords(tableName);
        if (coords == null) return;

        // 整行所有列清为空气（空气 = 空行，读取时跳过）
        for (int colIdx = 0; colIdx < schema.size(); colIdx++) {
            int x = coords.xStart + colIdx;
            for (int y = WorldUtils.DATA_START_Y; y <= WorldUtils.DATA_MAX_Y; y++) {
                blockOp.clearBlock(x, y, (int) z);
            }
        }

        blockOp.flush();
    }

    // === Encoding/Decoding helpers ===

    private List<DyeColor> encodeValue(Object value, String dataType) {
        if (value == null) return WoolEncoder.encodeNull();

        String typeBase = dataType.split("\\(")[0].toUpperCase();

        return switch (typeBase) {
            case "INT", "INTEGER" -> WoolEncoder.encodeInt(((Number) value).intValue());
            case "BIGINT" -> WoolEncoder.encodeBigInt(((Number) value).longValue());
            case "FLOAT" -> WoolEncoder.encodeFloat(((Number) value).floatValue());
            case "DOUBLE" -> WoolEncoder.encodeDouble(((Number) value).doubleValue());
            case "BOOLEAN", "BOOL" -> WoolEncoder.encodeBoolean((Boolean) value);
            case "VARCHAR", "TEXT", "STRING" -> WoolEncoder.encodeString(value.toString());
            case "DATE", "DATETIME" -> {
                if (value instanceof Number n) {
                    yield WoolEncoder.encodeTimestamp(n.longValue());
                }
                yield WoolEncoder.encodeString(value.toString());
            }
            default -> WoolEncoder.encodeString(value.toString());
        };
    }

    private Object decodeValue(List<DyeColor> colors, String dataType) {
        if (colors.isEmpty()) return null;
        if (colors.get(0) == DyeColor.BLACK) return null;

        String typeBase = dataType.split("\\(")[0].toUpperCase();

        return switch (typeBase) {
            case "INT", "INTEGER" -> WoolDecoder.decodeInt(colors);
            case "BIGINT" -> WoolDecoder.decodeBigInt(colors);
            case "FLOAT" -> WoolDecoder.decodeFloat(colors);
            case "DOUBLE" -> WoolDecoder.decodeDouble(colors);
            case "BOOLEAN", "BOOL" -> WoolDecoder.decodeBoolean(colors.get(0));
            case "VARCHAR", "TEXT", "STRING" -> WoolDecoder.decodeString(colors);
            case "DATE", "DATETIME" -> WoolDecoder.decodeTimestamp(colors);
            default -> WoolDecoder.decodeString(colors);
        };
    }
}
