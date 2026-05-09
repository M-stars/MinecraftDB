package com.minecraftdb.database;

import com.minecraftdb.config.ModConfig;
import com.minecraftdb.sql.SqlExecutor;
import com.minecraftdb.storage.CoordinateAllocator;
import com.minecraftdb.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

/**
 * 顶层数据库管理器。
 * 一个超平坦世界 = 一个数据库实例。
 * 世界文件夹名 = 数据库名。
 */
public class DatabaseManager {

    private final MinecraftServer server;
    private ServerLevel databaseLevel;
    private String databaseName;
    private CoordinateAllocator coordAllocator;
    private TableManager tableManager;
    private SchemaManager schemaManager;
    private TransactionManager transactionManager;
    private SqlExecutor sqlExecutor;

    public DatabaseManager(MinecraftServer server) {
        this.server = server;
    }

    public void initialize() {
        String configuredWorld = ModConfig.DATABASE_DEFAULT_WORLD;

        // 精确匹配世界名
        ServerLevel found = findWorld(configuredWorld);
        if (found == null) {
            found = server.overworld(); // 回退到主世界
        }

        this.databaseLevel = found;
        this.databaseName = extractWorldName(found);

        // 初始化子系统
        this.coordAllocator = new CoordinateAllocator();
        this.tableManager = new TableManager(databaseLevel, coordAllocator, this);
        this.schemaManager = new SchemaManager(databaseLevel, coordAllocator);
        this.transactionManager = new TransactionManager(databaseLevel);
        this.sqlExecutor = new SqlExecutor(this);

        // 从文件恢复表坐标注册表
        Map<String, CoordinateAllocator.TableCoords> savedCoords = schemaManager.loadTableRegistry();
        if (!savedCoords.isEmpty()) {
            coordAllocator.loadFromExisting(savedCoords);
        }

        // 确保基岩层
        ensureBedrockLayer();

        // WAL 恢复
        transactionManager.recoverFromWAL();

        // 加载所有 Schema
        schemaManager.loadAllSchemas();

        // 验证注册表与基岩标记的一致性
        verifyTableBoundaries();
    }

    private ServerLevel findWorld(String worldName) {
        for (ServerLevel sl : server.getAllLevels()) {
            if (extractWorldName(sl).equalsIgnoreCase(worldName)) {
                return sl;
            }
        }
        return null;
    }

    private String extractWorldName(ServerLevel level) {
        return level.dimension().location().getPath();
    }

    private void ensureBedrockLayer() {
        for (int x = 0; x < 200; x++) {
            for (int z = 0; z < 200; z++) {
                BlockPos pos = new BlockPos(x, WorldUtils.BEDROCK_Y, z);
                if (databaseLevel.getBlockState(pos).isAir()) {
                    databaseLevel.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 3);
                }
            }
        }
    }

    /**
     * 为已注册的每个表验证并放置基岩边界标记
     */
    private void verifyTableBoundaries() {
        for (CoordinateAllocator.TableCoords coords : coordAllocator.getAllCoords().values()) {
            for (int x = coords.xStart; x <= coords.xEnd; x++) {
                BlockPos pos = new BlockPos(x, 0, 0);
                databaseLevel.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 3);
            }
            // 在 Y=255 放一个标记块（金块），方便在空中看到表范围
            BlockPos markerPos = new BlockPos(coords.xStart, 255, 0);
            databaseLevel.setBlock(markerPos, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        }
    }

    /**
     * 保存表注册表（创建/删除表后调用）
     */
    public void saveTableRegistry() {
        schemaManager.saveTableRegistry(coordAllocator.getAllCoords());
    }

    public void shutdown() {
        if (tableManager != null) {
            tableManager.getBlockOperator().flush();
        }
        if (transactionManager != null && transactionManager.isInTransaction()) {
            try {
                transactionManager.commit();
            } catch (Exception ignored) {}
        }
        // 持久化表坐标映射
        saveTableRegistry();
    }

    // === Getters ===

    public String getDatabaseName() {
        return databaseName;
    }

    public ServerLevel getDatabaseLevel() {
        return databaseLevel;
    }

    public CoordinateAllocator getCoordAllocator() {
        return coordAllocator;
    }

    public TableManager getTableManager() {
        return tableManager;
    }

    public SchemaManager getSchemaManager() {
        return schemaManager;
    }

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    public SqlExecutor getSqlExecutor() {
        return sqlExecutor;
    }
}
