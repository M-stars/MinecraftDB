package com.minecraftdb.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 待应用方块写入日志（纯数据结构，可脱离 Minecraft 单测）。
 *
 * <p>批量模式下 BlockOperator 的写入先进入本日志而非直接落世界：
 * <ul>
 *   <li><b>FIFO</b>：按插入序排水，全局（跨语句/跨会话）保持方块操作顺序；</li>
 *   <li><b>读穿覆盖</b>：{@link #effectiveColor} 让同语句内「写后读」立即看到新值
 *       （方块尚未真正落世界）；</li>
 *   <li><b>事务清除</b>：回滚时按 txnId 移除尚未应用的写入，防止排水把已回滚的数据写到世界；</li>
 *   <li><b>预算排水</b>：{@link #drain} 每 tick 只应用 limit 个，把大量方块变更摊到多个 tick。</li>
 * </ul>
 */
public class PendingWriteLog {

    /** 一条待应用写入；color == null 表示清除为空气 */
    public record Entry(long txnId, BlockPos pos, DyeColor color) {}

    /** 插入序保持：LinkedHashMap；同一 pos 再次写入更新值但不改变位置（FIFO 语义不变） */
    private final LinkedHashMap<BlockPos, Entry> pending = new LinkedHashMap<>();

    // === 写入 ===

    /**
     * 记录一条写入（调用方保证已做过「值相同跳过」与 WAL 记录）。
     * 返回覆盖前该 pos 是否已有待应用写入。
     */
    public boolean add(long txnId, BlockPos pos, DyeColor color) {
        boolean existed = pending.containsKey(pos);
        pending.put(pos, new Entry(txnId, pos, color));
        return existed;
    }

    // === 读取（读穿覆盖） ===

    /** 该 pos 当前的待应用值；无待应用写入时返回 null（应读世界） */
    public DyeColor pendingColor(BlockPos pos) {
        Entry e = pending.get(pos);
        return e != null ? e.color() : null;
    }

    public boolean hasPending(BlockPos pos) {
        return pending.containsKey(pos);
    }

    public int size() {
        return pending.size();
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    // === 排水 ===

    /**
     * 按插入序弹出至多 limit 条交给 applier。返回实际应用条数。
     * limit &lt;= 0 表示全部应用。
     */
    public int drain(int limit, Applier applier) {
        int applied = 0;
        Iterator<Map.Entry<BlockPos, Entry>> it = pending.entrySet().iterator();
        while (it.hasNext() && (limit <= 0 || applied < limit)) {
            Entry e = it.next().getValue();
            it.remove();
            applier.apply(e.pos(), e.color());
            applied++;
        }
        return applied;
    }

    public interface Applier {
        void apply(BlockPos pos, DyeColor color);
    }

    // === 事务清除 ===

    /** 移除某事务的所有待应用写入（回滚路径），返回移除条数 */
    public int purgeTxn(long txnId) {
        int removed = 0;
        Iterator<Map.Entry<BlockPos, Entry>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().txnId() == txnId) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** 清空（服务器关闭前强制全量排水后不应残留；防御性使用） */
    public void clear() {
        pending.clear();
    }
}
