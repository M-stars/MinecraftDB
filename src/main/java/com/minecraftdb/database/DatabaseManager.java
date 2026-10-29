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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

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

        // 初始化子系统（transactionManager 必须先于 tableManager：BlockOperator 的
        // 方块写入路径依赖它记录 WAL）
        this.coordAllocator = new CoordinateAllocator();
        this.transactionManager = new TransactionManager(databaseLevel);
        this.tableManager = new TableManager(databaseLevel, coordAllocator, this, transactionManager,
                ModConfig.PERFORMANCE_BATCH_BLOCK_UPDATES);
        this.schemaManager = new SchemaManager(databaseLevel, coordAllocator);
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

    /**
     * 在服务端主线程执行 SQL。HTTP/TCP 线程必须经由本方法访问数据库：
     * Minecraft 的 Level/setBlock 只允许主线程访问，跨线程读写 chunk
     * 会导致并发修改异常、崩溃或存档损坏。
     */
    public SqlExecutor.QueryResult executeSql(String sql) {
        return callOnServerThread(() -> sqlExecutor.execute(sql));
    }

    /**
     * 在服务端主线程执行任务并同步等待结果。已在主线程则直接执行。
     */
    public <T> T callOnServerThread(Supplier<T> task) {
        if (server.isSameThread()) {
            return task.get();
        }
        try {
            return server.submit(task::get).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("数据库执行被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new RuntimeException("数据库任务在主线程排队超时（任务可能仍会执行，写入结果未知）");
        }
    }

    public String getDatabaseName() {
        return databaseName;
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
