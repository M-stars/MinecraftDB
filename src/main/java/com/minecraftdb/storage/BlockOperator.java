package com.minecraftdb.storage;

import com.minecraftdb.config.ModConfig;
import com.minecraftdb.database.TransactionManager;
import com.minecraftdb.database.TransactionManager.Transaction;
import com.minecraftdb.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;

import java.util.Map;

/**
 * Encapsulates block read/write operations with batching support.
 *
 * <p>批量模式下写入先进 {@link PendingWriteLog}（不直接落世界）：
 * <ul>
 *   <li>WAL 在<b>入队时</b>记录（先日志后落块，崩溃恢复才完整）；</li>
 *   <li>读取经覆盖层（{@link #readWool}），同语句内「写后读」立即可见；</li>
 *   <li>{@link #flush()} 在语句边界把待应用写入<b>全部同步应用</b>——与旧语义一致，
 *       INSERT/UPDATE/DELETE/TRUNCATE 的世界状态在语句返回时即完整；</li>
 *   <li>整表清除等大批量操作（DROP TABLE）改走 {@link #flushDeferred()}：
 *       待应用写入留给 {@link #drain} 由服务端每 tick 按预算排水，
 *       把上万方块清除摊到多个 tick，避免单 tick 卡顿。</li>
 * </ul>
 *
 * <p>事务回滚时按 txnId 清除尚未应用的写入（经 TransactionManager 的中止回调），
 * 防止排水把已回滚的数据写到世界。非批量模式（调试用）保持逐块立即落盘。
 */
public class BlockOperator {

    private final ServerLevel level;
    private final boolean batchMode;
    private final TransactionManager txnManager;
    private final PendingWriteLog pendingLog = new PendingWriteLog();

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
            enqueue(pos, color);
        } else {
            applyWrite(pos, color);
        }
    }

    /**
     * 将方块清除为空气。批量队列中以 color == null 表示清除操作。
     */
    public void clearBlock(BlockPos pos) {
        if (batchMode) {
            enqueue(pos, null);
        } else {
            applyWrite(pos, null);
        }
    }

    public void clearBlock(int x, int y, int z) {
        clearBlock(new BlockPos(x, y, z));
    }

    /**
     * 批量模式：事务写缓冲优先（MVCC——事务内写入不落世界，提交时统一入全局队列），
     * 否则进待应用日志。WAL 均同步入队。目标状态相同则跳过。
     */
    private void enqueue(BlockPos pos, DyeColor color) {
        DyeColor effective = effectiveColor(pos);
        if (effective == color) return;

        long txnId = -1;
        Map<BlockPos, Transaction.OverlayEntry> overlay =
                txnManager != null ? txnManager.currentTxnOverlay() : null;
        if (overlay != null) {
            // 写缓冲：保留首次写入的旧色（冲突检测基准），更新最终值
            Transaction.OverlayEntry existing = overlay.get(pos);
            DyeColor expectedOld = existing != null ? existing.expectedOld() : effective;
            overlay.put(pos, new Transaction.OverlayEntry(expectedOld, color));
            txnId = txnManager.currentTxnId();
        } else {
            txnId = txnManager != null ? txnManager.currentTxnId() : -1;
            pendingLog.add(txnId, pos, color);
        }
        if (txnManager != null) {
            txnManager.logBlockChange("", pos.getX(), pos.getY(), pos.getZ(),
                    effective == null ? null : effective.name(),
                    color == null ? null : color.name());
        }
    }

    /** 覆盖优先级：事务写缓冲 → 全局待应用 → 世界 */
    private DyeColor effectiveColor(BlockPos pos) {
        if (txnManager != null) {
            Map<BlockPos, Transaction.OverlayEntry> overlay = txnManager.currentTxnOverlay();
            if (overlay != null && overlay.containsKey(pos)) {
                return overlay.get(pos).color();
            }
        }
        if (pendingLog.hasPending(pos)) return pendingLog.pendingColor(pos);
        return WorldUtils.getWoolColor(level, pos);
    }

    /**
     * Flush all pending batched block updates to the world.
     * 语句边界语义：待应用写入全部同步应用（与逐块落盘等效），
     * 并把本批变更对应的 WAL 缓冲推到操作系统。
     */
    public void flush() {
        if (batchMode) {
            pendingLog.drain(0, this::applyDeferred);
        }
        if (txnManager != null) {
            txnManager.flushWALBuffer();
        }
    }

    /**
     * 延迟模式：不立即应用，把待应用写入留给 {@link #drain} 分 tick 排水。
     * 用于整表清除等大批量操作；调用方必须保证随后的读取不再依赖这些方块
     * （如表已从注册表移除）。
     */
    public void flushDeferred() {
        if (txnManager != null) {
            txnManager.flushWALBuffer();
        }
        // 待应用写入保留在日志中，由 tick 排水
    }

    /**
     * 每 tick 排水：应用至多 budget 条待应用写入（budget<=0 表示全部）。
     * 由服务端 tick 事件驱动，返回本 tick 实际应用条数。
     */
    public int drain(int budget) {
        if (!batchMode || pendingLog.isEmpty()) return 0;
        int applied = pendingLog.drain(budget <= 0 ? 0 : budget, this::applyDeferred);
        if (pendingLog.isEmpty() && txnManager != null) {
            txnManager.flushWALBuffer();
        }
        return applied;
    }

    public int pendingSize() {
        return pendingLog.size();
    }

    /** 事务回滚/会话中断：清除该事务尚未应用的写入，返回清除条数 */
    public int purgeTxn(long txnId) {
        return pendingLog.purgeTxn(txnId);
    }

    /** 应用一条已在入队时记录过 WAL 的延迟写入（不重复记 WAL） */
    private void applyDeferred(BlockPos pos, DyeColor color) {
        if (color == null) {
            WorldUtils.setAir(level, pos);
        } else {
            WorldUtils.setWool(level, pos, color);
        }
    }

    /**
     * 应用单个方块变更（非批量模式）：读取旧颜色 → 记录 WAL → 落盘。目标状态相同则跳过。
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
        // 读穿覆盖：事务写缓冲（读己之写）→ 全局待应用（分 tick 排水中）→ 世界
        if (txnManager != null) {
            Map<BlockPos, Transaction.OverlayEntry> overlay = txnManager.currentTxnOverlay();
            if (overlay != null && overlay.containsKey(pos)) {
                return overlay.get(pos).color(); // null = 缓冲的清除（空气）
            }
        }
        if (batchMode && pendingLog.hasPending(pos)) {
            return pendingLog.pendingColor(pos); // null = 待应用清除（空气）
        }
        return WorldUtils.getWoolColor(level, pos);
    }

    /**
     * 事务提交：把会话写缓冲移入全局排水队列（冲突检测已由 TransactionManager 完成）。
     * 此后方块变更经分 tick 排水落世界；读取经全局待应用覆盖立即可见。
     */
    public void commitOverlay(long txnId,
                              Map<BlockPos, Transaction.OverlayEntry> overlay) {
        for (Map.Entry<BlockPos, Transaction.OverlayEntry> e : overlay.entrySet()) {
            pendingLog.add(txnId, e.getKey(), e.getValue().color());
        }
        overlay.clear();
    }
}
