package com.minecraftdb.database;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

/**
 * WAL 文件分析（纯函数，可脱离 Minecraft 单测）：
 * 给定一个事务 WAL 文件的逐行文本，判定事务状态与恢复动作。
 *
 * <p>判定规则（与 TransactionManager 的写入约定一致）：
 * <ul>
 *   <li>含 COMMIT 行 → 已提交：按 OP 顺序重放 newColor（幂等，覆盖分 tick 排水中断的场景）；</li>
 *   <li>无 COMMIT 行且解析无损 → 未提交：按逆序恢复 oldColor；</li>
 *   <li>任一行解析失败 → 损坏：不恢复不删除，保留为 .corrupt 待查。</li>
 * </ul>
 */
public final class WalRecovery {

    private static final Gson GSON = new Gson();

    /** 分析结果：ops 为 OP 条目（文件顺序）；opsReversed 为逆序（未提交恢复用） */
    public record Plan(List<TransactionManager.WALEntry> ops,
                       List<TransactionManager.WALEntry> opsReversed,
                       boolean committed, boolean corrupt) {}

    private WalRecovery() {}

    public static Plan analyze(List<String> lines) {
        List<TransactionManager.WALEntry> ops = new ArrayList<>();
        boolean committed = false;
        boolean corrupt = false;

        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            try {
                TransactionManager.WALEntry e = GSON.fromJson(line, TransactionManager.WALEntry.class);
                if (e == null) {
                    corrupt = true;
                    break;
                }
                if ("COMMIT".equals(e.type)) {
                    committed = true;
                    break;
                }
                if ("OP".equals(e.type)) {
                    ops.add(e);
                }
                // BEGIN 行：无操作
            } catch (Exception parseError) {
                corrupt = true;
                break;
            }
        }

        List<TransactionManager.WALEntry> reversed = new ArrayList<>(ops);
        java.util.Collections.reverse(reversed);
        return new Plan(ops, reversed, committed, corrupt);
    }
}
