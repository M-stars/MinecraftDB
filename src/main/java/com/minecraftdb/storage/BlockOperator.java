package com.minecraftdb.storage;

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
 */
public class BlockOperator {

    private final ServerLevel level;
    private final boolean batchMode;
    private final Queue<PendingBlock> pendingQueue = new ConcurrentLinkedQueue<>();

    public BlockOperator(ServerLevel level) {
        this.level = level;
        this.batchMode = true;
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
            WorldUtils.setWool(level, pos, color);
        }
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
        int count = 0;
        while ((pb = pendingQueue.poll()) != null) {
            WorldUtils.setWool(level, pb.pos, pb.color);
            count++;
        }
        if (count > 0) {
            // Log only for large batches to avoid spam
        }
    }

    public int pendingCount() {
        return pendingQueue.size();
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
        WorldUtils.fillAreaWool(level, xStart, xEnd, yStart, yEnd, zStart, zEnd, DyeColor.WHITE);
    }

    public void initializeTableArea(int xStart, int xEnd, int zEnd) {
        WorldUtils.fillAreaWool(level, xStart, xEnd, WorldUtils.DATA_START_Y, WorldUtils.DATA_MAX_Y, 1, (int) zEnd,
                DyeColor.WHITE);
    }

    private record PendingBlock(BlockPos pos, DyeColor color) {}
}
