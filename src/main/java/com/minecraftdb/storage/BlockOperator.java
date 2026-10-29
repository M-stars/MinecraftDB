package com.minecraftdb.storage;

import com.minecraftdb.database.TransactionManager;
import com.minecraftdb.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Encapsulates block read/write operations with batching support.
 * 所有变更在应用时记录进当前事务的 WAL（旧颜色 → 新颜色），供回滚与崩溃恢复使用。
 */
public class BlockOperator {

    private final ServerLevel level;
    private final boolean batchMode;
    private final TransactionManager txnManager;
    private final Queue<PendingBlock> pendingQueue = new ConcurrentLinkedQueue<>();

    public BlockOperator(ServerLevel level, TransactionManager txnManager, boolean batchMode) {
        this.level = level;
        this.txnManager = txnManager;
        this.batchMode = batchMode;
    }

    // === Write operations ===

    public void writeWool(int x, int y, int z, DyeColor color) {
        writeWool(new BlockPos(x, y, z), color);
    }

    public void writeWool(BlockPos pos, DyeColor color) {
        if (batchMode) {
            pendingQueue.add(new PendingBlock(pos, color));
        } else {
            applyWrite(pos, color);
        }
    }

    /**
     * 将方块清除为空气。批量队列中以 color == null 表示清除操作。
     */
    public void clearBlock(BlockPos pos) {
        if (batchMode) {
            pendingQueue.add(new PendingBlock(pos, null));
        } else {
            applyWrite(pos, null);
        }
    }

    public void clearBlock(int x, int y, int z) {
        clearBlock(new BlockPos(x, y, z));
    }

    /**
     * Flush all pending batched block updates to the world.
     */
    public void flush() {
        PendingBlock pb;
        while ((pb = pendingQueue.poll()) != null) {
            applyWrite(pb.pos(), pb.color());
        }
        // 语句边界：把本批变更对应的 WAL 缓冲推到操作系统
        if (txnManager != null) {
            txnManager.flushWALBuffer();
        }
    }

    /**
     * 丢弃尚未应用（未 flush）的批量写入。语句失败回滚后必须调用，
     * 否则队列里的残留写入会在下一次 flush 时静默落到世界。
     */
    public void clearPending() {
        pendingQueue.clear();
    }

    /**
     * 应用单个方块变更：读取旧颜色 → 记录 WAL → 落盘。目标状态相同则跳过。
     */
    private void applyWrite(BlockPos pos, DyeColor color) {
        DyeColor old = WorldUtils.getWoolColor(level, pos);
        if (old == color) return;

        if (txnManager != null) {
            txnManager.logBlockChange("", pos.getX(), pos.getY(), pos.getZ(),
                    old == null ? null : old.name(),
                    color == null ? null : color.name());
        }
        if (color == null) {
            WorldUtils.setAir(level, pos);
        } else {
            WorldUtils.setWool(level, pos, color);
        }
    }

    // === Read operations ===

    public DyeColor readWool(int x, int y, int z) {
        return readWool(new BlockPos(x, y, z));
    }

    public DyeColor readWool(BlockPos pos) {
        return WorldUtils.getWoolColor(level, pos);
    }

    private record PendingBlock(BlockPos pos, DyeColor color) {}
}
