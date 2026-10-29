package com.minecraftdb.database;

import com.google.gson.Gson;
import com.minecraftdb.config.ModConfig;
import com.minecraftdb.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ACID 事务管理器（WAL + 锁管理）。
 *
 * WAL 设计：每个事务一个文件 config/minecraftdb/wal/<世界名>/txn_<id>.wal，
 * 逐行 JSON：
 *   BEGIN 行:  {"type":"BEGIN","txnId":1}
 *   数据行:    {"type":"OP","txnId":1,"table":"users","x":1,"y":2,"z":3,"oldColor":"ORANGE","newColor":"AIR"}
 *   COMMIT 行: {"type":"COMMIT","txnId":1}
 *
 * 写入经 BufferedWriter 缓冲：每条 OP 只做一次 write 调用（无逐条打开/关闭文件的
 * syscall 开销），由 flushWALBuffer() 在语句边界把缓冲推到操作系统。
 *
 * 提交流程：追加 COMMIT 行并 flush 后关闭、删除文件。若 flush/写 COMMIT 失败，
 * 保留文件、抛出异常、事务保持开启（COMMIT 行不存在 → 崩溃恢复会回滚）。
 * 崩溃恢复时：
 *   - 文件存在且包含 COMMIT 行 → 事务已提交，保留数据，删除文件
 *   - 否则 → 未提交，按逆序把每个 OP 的方块恢复为 oldColor（null 表示空气）
 *   - 解析损坏的文件改名保留为 .corrupt，不做部分恢复也不删除
 */
public class TransactionManager {

    private final ServerLevel level;
    private final boolean walEnabled;
    private final Gson gson = new Gson();
    private final Path walDir;

    // 锁管理
    private final Map<String, ReentrantReadWriteLock> tableLocks = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, ReentrantReadWriteLock>> rowLocks = new ConcurrentHashMap<>();

    // 事务状态
    private final ThreadLocal<Transaction> currentTxn = new ThreadLocal<>();
    private final Map<Long, Transaction> activeTransactions = new ConcurrentHashMap<>();
    private long nextTxnId = 1;

    // 当前线程事务的 WAL 写入器（与 currentTxn 同为线程绑定）
    private final ThreadLocal<BufferedWriter> walWriter = new ThreadLocal<>();

    public TransactionManager(ServerLevel level) {
        this.level = level;
        this.walEnabled = ModConfig.DATABASE_WAL_ENABLED;
        this.walDir = Paths.get("config", "minecraftdb", "wal", extractWorldName());
        try {
            Files.createDirectories(walDir);
        } catch (IOException ignored) {}
    }

    private String extractWorldName() {
        return level.dimension().location().getPath();
    }

    // === 事务生命周期 ===

    public void begin() {
        if (currentTxn.get() != null) {
            throw new IllegalStateException("已有活跃事务");
        }
        Transaction txn = new Transaction(nextTxnId++);
        currentTxn.set(txn);
        activeTransactions.put(txn.id, txn);

        if (walEnabled) {
            try {
                openWALWriter(txn.id, true);
                writeWALLine(WALEntry.begin(txn.id));
                flushWALBuffer();
            } catch (IOException e) {
                closeWALWriter();
                throw new RuntimeException("WAL 初始化失败，无法开始事务", e);
            }
        }
    }

    public void commit() {
        Transaction txn = currentTxn.get();
        if (txn == null) {
            throw new IllegalStateException("没有活跃事务");
        }

        if (walEnabled) {
            try {
                writeWALLine(WALEntry.commit(txn.id));
                flushWALBuffer();
            } catch (IOException e) {
                // 保留 WAL 文件（无 COMMIT 行），崩溃恢复时按未提交回滚
                closeWALWriter();
                throw new RuntimeException("WAL 提交失败，事务未提交，请 ROLLBACK 后重试", e);
            }
            closeWALWriter();
            deleteWALFile(txn.id);
        }

        releaseAllLocks(txn);
        activeTransactions.remove(txn.id);
        currentTxn.remove();
    }

    public void rollback() {
        Transaction txn = currentTxn.get();
        if (txn == null) {
            throw new IllegalStateException("没有活跃事务");
        }

        closeWALWriter();
        if (walEnabled) {
            deleteWALFile(txn.id);
        }

        // 逆序恢复所有已应用的方块变更
        for (int i = txn.walEntries.size() - 1; i >= 0; i--) {
            restoreEntry(txn.walEntries.get(i));
        }

        releaseAllLocks(txn);
        activeTransactions.remove(txn.id);
        currentTxn.remove();
    }

    public boolean isInTransaction() {
        return currentTxn.get() != null;
    }

    // === 方块变更日志 ===

    /**
     * 记录一次方块变更，必须在变更落盘之前调用。
     * oldColor/newColor 为 DyeColor 枚举名，null 表示空气。
     * 无论 WAL 是否启用都写入内存列表（回滚需要）；WAL 启用时写入文件缓冲。
     * IO 失败直接抛出：调用方（BlockOperator → SqlExecutor）会中止并回滚当前事务，
     * 避免"日志缺失但数据已写"的静默不一致。
     */
    public void logBlockChange(String tableName, int x, int y, int z, String oldColor, String newColor) {
        Transaction txn = currentTxn.get();
        if (txn == null) return;

        WALEntry entry = WALEntry.op(txn.id, tableName, x, y, z, oldColor, newColor);
        txn.walEntries.add(entry);
        if (walEnabled) {
            try {
                if (walWriter.get() == null) {
                    openWALWriter(txn.id, false);
                }
                writeWALLine(entry);
            } catch (IOException e) {
                throw new RuntimeException("WAL 写入失败，中止当前事务", e);
            }
        }
    }

    /**
     * 把 WAL 缓冲推到操作系统。方块批量写入（语句边界）后调用，
     * 保证已应用到世界的数据都有对应的持久日志。
     */
    public void flushWALBuffer() {
        BufferedWriter writer = walWriter.get();
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException e) {
                throw new RuntimeException("WAL 刷写失败，中止当前事务", e);
            }
        }
    }

    private void restoreEntry(WALEntry entry) {
        BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
        if (entry.oldColor == null) {
            level.removeBlock(pos, false);
        } else {
            WorldUtils.setWool(level, pos, DyeColor.valueOf(entry.oldColor));
        }
    }

    private void releaseAllLocks(Transaction txn) {
        for (String table : txn.acquiredTableReadLocks) {
            ReentrantReadWriteLock lock = tableLocks.get(table);
            if (lock != null) lock.readLock().unlock();
        }
        for (String table : txn.acquiredTableWriteLocks) {
            ReentrantReadWriteLock lock = tableLocks.get(table);
            if (lock != null) lock.writeLock().unlock();
        }
        for (RowLockKey rowKey : txn.acquiredRowLocks) {
            releaseRowLock(rowKey.table, rowKey.z);
        }
    }

    // === 锁管理 ===

    public void acquireTableReadLock(String tableName) {
        ReentrantReadWriteLock lock = tableLocks.computeIfAbsent(tableName, k -> new ReentrantReadWriteLock());
        lock.readLock().lock();
        Transaction txn = currentTxn.get();
        if (txn != null) {
            txn.acquiredTableReadLocks.add(tableName);
        }
    }

    public void releaseTableReadLock(String tableName) {
        ReentrantReadWriteLock lock = tableLocks.get(tableName);
        if (lock != null) lock.readLock().unlock();
    }

    public void acquireTableWriteLock(String tableName) {
        ReentrantReadWriteLock lock = tableLocks.computeIfAbsent(tableName, k -> new ReentrantReadWriteLock());
        lock.writeLock().lock();
        Transaction txn = currentTxn.get();
        if (txn != null) {
            txn.acquiredTableWriteLocks.add(tableName);
        }
    }

    public void releaseTableWriteLock(String tableName) {
        ReentrantReadWriteLock lock = tableLocks.get(tableName);
        if (lock != null) lock.writeLock().unlock();
    }

    public void acquireRowLock(String tableName, long z) {
        Map<Long, ReentrantReadWriteLock> rowLockMap = rowLocks.computeIfAbsent(tableName, k -> new ConcurrentHashMap<>());
        ReentrantReadWriteLock lock = rowLockMap.computeIfAbsent(z, k -> new ReentrantReadWriteLock());
        lock.writeLock().lock();
        Transaction txn = currentTxn.get();
        if (txn != null) {
            txn.acquiredRowLocks.add(new RowLockKey(tableName, z));
        }
    }

    public void releaseRowLock(String tableName, long z) {
        Map<Long, ReentrantReadWriteLock> rowLockMap = rowLocks.get(tableName);
        if (rowLockMap != null) {
            ReentrantReadWriteLock lock = rowLockMap.get(z);
            if (lock != null) lock.writeLock().unlock();
        }
    }

    // === WAL 文件操作 ===

    private void openWALWriter(long txnId, boolean truncate) throws IOException {
        closeWALWriter();
        Path file = walDir.resolve("txn_" + txnId + ".wal");
        walWriter.set(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                truncate ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND));
    }

    private void writeWALLine(WALEntry entry) throws IOException {
        BufferedWriter writer = walWriter.get();
        writer.write(gson.toJson(entry));
        writer.write(System.lineSeparator());
    }

    private void closeWALWriter() {
        BufferedWriter writer = walWriter.get();
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {}
            walWriter.remove();
        }
    }

    private void deleteWALFile(long txnId) {
        try {
            Files.deleteIfExists(walDir.resolve("txn_" + txnId + ".wal"));
        } catch (IOException ignored) {}
    }

    /**
     * 服务端启动时恢复未完成的事务（在主线程、任何数据读取之前调用）。
     * 解析损坏的文件改名为 .corrupt 保留待查，不做部分恢复也不删除。
     */
    public void recoverFromWAL() {
        if (!walEnabled) return;

        try {
            if (!Files.exists(walDir)) return;

            try (var files = Files.list(walDir)) {
                files.filter(f -> f.getFileName().toString().startsWith("txn_")
                                  && f.getFileName().toString().endsWith(".wal"))
                     .forEach(f -> {
                         try {
                             List<WALEntry> entries = new ArrayList<>();
                             boolean committed = false;
                             boolean corrupt = false;
                             for (String line : Files.readAllLines(f)) {
                                 if (line.isBlank()) continue;
                                 try {
                                     WALEntry e = gson.fromJson(line, WALEntry.class);
                                     if (e == null) {
                                         corrupt = true;
                                         break;
                                     }
                                     if ("COMMIT".equals(e.type)) {
                                         committed = true;
                                         break;
                                     }
                                     if ("OP".equals(e.type)) {
                                         entries.add(e);
                                     }
                                 } catch (Exception parseError) {
                                     corrupt = true;
                                     break;
                                 }
                             }
                             if (!corrupt && !committed) {
                                 for (int i = entries.size() - 1; i >= 0; i--) {
                                     restoreEntry(entries.get(i));
                                 }
                             }
                             if (corrupt) {
                                 Files.move(f, f.resolveSibling(f.getFileName() + ".corrupt"),
                                         java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                             } else {
                                 Files.deleteIfExists(f);
                             }
                         } catch (Exception ignored) {}
                     });
            }
        } catch (IOException ignored) {}
    }

    // === 内部类 ===

    public static class Transaction {
        public final long id;
        public final long timestamp;
        public final List<String> acquiredTableReadLocks = new ArrayList<>();
        public final List<String> acquiredTableWriteLocks = new ArrayList<>();
        public final List<RowLockKey> acquiredRowLocks = new ArrayList<>();
        public final List<WALEntry> walEntries = new ArrayList<>();

        public Transaction(long id) {
            this.id = id;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public record RowLockKey(String table, long z) {}

    /** WAL 行。type: BEGIN / OP / COMMIT；oldColor/newColor 为 null 表示空气。 */
    public static class WALEntry {
        public String type;
        public long txnId;
        public String table;
        public int x, y, z;
        public String oldColor;
        public String newColor;

        public WALEntry() {}

        public static WALEntry begin(long txnId) {
            WALEntry e = new WALEntry();
            e.type = "BEGIN";
            e.txnId = txnId;
            return e;
        }

        public static WALEntry commit(long txnId) {
            WALEntry e = new WALEntry();
            e.type = "COMMIT";
            e.txnId = txnId;
            return e;
        }

        public static WALEntry op(long txnId, String table, int x, int y, int z, String oldColor, String newColor) {
            WALEntry e = new WALEntry();
            e.type = "OP";
            e.txnId = txnId;
            e.table = table;
            e.x = x;
            e.y = y;
            e.z = z;
            e.oldColor = oldColor;
            e.newColor = newColor;
            return e;
        }
    }
}
