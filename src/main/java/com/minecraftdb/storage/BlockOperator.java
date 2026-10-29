package com.minecraftdb.storage;

import com.minecraftdb.database.TransactionManager;
import com.minecraftdb.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;
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

    public ServerLevel getLevel() {
        return level;
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

    public void writeRow(int xStart, int yBase, int z, List<DyeColor>[] columns) {
        for (int col = 0; col < columns.length; col++) {
            List<DyeColor> colorSeq = columns[col];
            if (colorSeq == null) continue;
            int x = xStart + col;
            for (int dy = 0; dy < colorSeq.size(); dy++) {
                DyeColor color = colorSeq.get(dy);
                if (color != null) {
                    writeWool(x, yBase + dy, z, color);
                }
            }
        }
    }

    public void fillRow(int xStart, int xEnd, int y, int z, DyeColor color) {
        WorldUtils.fillWool(level, xStart, xEnd, y, z, z, color);
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

    public int pendingCount() {
        return pendingQueue.size();
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

    public List<DyeColor> readColumn(int x, int yStart, int yEnd, int z) {
        List<DyeColor> colors = new ArrayList<>();
        for (int y = yStart; y <= yEnd; y++) {
            DyeColor color = readWool(x, y, z);
            if (color == null) break;
            colors.add(color);
        }
        return colors;
    }

    public List<DyeColor>[] readRow(int xStart, int columnCount, int yBase, int z) {
        @SuppressWarnings("unchecked")
        List<DyeColor>[] columns = new List[columnCount];
        for (int col = 0; col < columnCount; col++) {
            List<DyeColor> colors = new ArrayList<>();
            for (int y = yBase; y < 256; y++) {
                DyeColor color = readWool(xStart + col, y, z);
                if (color == null) break;
                colors.add(color);
            }
            columns[col] = colors;
        }
        return columns;
    }

    // === Bulk operations ===

    public void clearArea(int xStart, int xEnd, int yStart, int yEnd, int zStart, int zEnd) {
        WorldUtils.fillAreaAir(level, xStart, xEnd, yStart, yEnd, zStart, zEnd);
    }

    public void initializeTableArea(int xStart, int xEnd, int zEnd) {
        WorldUtils.fillAreaAir(level, xStart, xEnd, WorldUtils.DATA_START_Y, WorldUtils.DATA_MAX_Y, 1, (int) zEnd);
    }

    private record PendingBlock(BlockPos pos, DyeColor color) {}
}
