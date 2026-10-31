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
 * ACID 事务管理器（WAL + 锁管理），按客户端会话隔离事务。
 *
 * 会话模型：每个客户端（游戏内玩家、TCP 连接、携带 X-MinecraftDB-Session 的 HTTP 客户端）
 * 拥有独立的会话 ID 与独立事务。SqlExecutor 在每次执行语句前通过 setCurrentSession 标明
 * 语句所属会话——所有语句统一在服务端主线程串行执行，因此用 ThreadLocal 承载
 * "当前会话"是安全的。
 *
 * WAL 设计：每个事务一个文件 config/minecraftdb/wal/<世界名>/txn_<id>.wal，
 * 逐行 JSON：
 *   BEGIN 行:  {"type":"BEGIN","txnId":1}
 *   数据行:    {"type":"OP","txnId":1,"table":"users","x":1,"y":2,"z":3,"oldColor":"ORANGE","newColor":"AIR"}
 *   COMMIT 行: {"type":"COMMIT","txnId":1}
 *
 * 写入经事务私有的 BufferedWriter 缓冲：每条 OP 只做一次 write 调用，
 * 由 flushWALBuffer() 在语句边界把缓冲推到操作系统。
 *
 * 提交流程：追加 COMMIT 行并 flush 后关闭、删除文件。若 flush/写 COMMIT 失败，
 * 保留文件、抛出异常、事务保持开启（COMMIT 行不存在 → 崩溃恢复会回滚）。
 * 崩溃恢复时：
 *   - 文件存在且包含 COMMIT 行 → 事务已提交，保留数据，删除文件
 *   - 否则 → 未提交，按逆序把每个 OP 的方块恢复为 oldColor（null 表示空气）
 *   - 解析损坏的文件改名保留为 .corrupt，不做部分恢复也不删除
 *
 * 已知限制：所有会话的语句在同一个主线程串行执行，且锁为线程级可重入——
 * 因此一个会话未提交的写入对其他会话可见（无 MVCC 快照）。
 */
public class TransactionManager {

    private final ServerLevel level;
    private final boolean walEnabled;
    private final Gson gson = new Gson();
    private final Path walDir;

    // 锁管理
    private final Map<String, ReentrantReadWriteLock> tableLocks = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, ReentrantReadWriteLock>> rowLocks = new ConcurrentHashMap<>();

    /** 事务中止（回滚/会话中断）回调：IndexManager 按触发的表整表失效（由 DatabaseManager 注入） */
    private java.util.function.Consumer<Iterable<String>> onTxnAborted;

    // 会话 → 活跃事务
    private final Map<String, Transaction> activeTxns = new ConcurrentHashMap<>();
    // 当前正在执行语句的会话（仅在主线程上读写）
    private final ThreadLocal<String> currentSession = new ThreadLocal<>();
    private long nextTxnId = 1;

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

    /** 由 DatabaseManager 在构造完成后注入：事务中止时使受影响的表索引失效 */
    public void setOnTxnAborted(java.util.function.Consumer<Iterable<String>> callback) {
        this.onTxnAborted = callback;
    }

    private void notifyTxnAborted(Transaction txn) {
        if (onTxnAborted != null && !txn.acquiredTableWriteLocks.isEmpty()) {
            onTxnAborted.accept(txn.acquiredTableWriteLocks);
        }
    }

    // === 会话绑定 ===

    /**
     * 由 SqlExecutor 在每次执行语句前调用：标识语句所属的客户端会话。
     * begin/commit/rollback/logBlockChange 都作用于该会话的事务。
     */
    public void setCurrentSession(String sessionId) {
        currentSession.set(sessionId);
    }

    public void clearCurrentSession() {
        currentSession.remove();
    }

    private String requireSession() {
        String session = currentSession.get();
        if (session == null) {
            throw new IllegalStateException("未设置数据库会话（编程错误）");
        }
        return session;
    }

    private Transaction currentTxn() {
        String session = currentSession.get();
        return session != null ? activeTxns.get(session) : null;
    }

    public boolean isInTransaction() {
        return currentTxn() != null;
    }

    /**
     * 结束客户端会话（玩家退出服务器 / TCP 连接断开）：
     * 存在未提交事务时立即回滚并移除，防止会话事务与 WAL 写入句柄永久泄漏。
     * 必须在主线程调用。
     */
    public void endSession(String sessionId) {
        Transaction txn = activeTxns.remove(sessionId);
        if (txn == null) return;
        notifyTxnAborted(txn);
        txnCloseWal(txn);
        if (walEnabled) {
            deleteWALFile(txn.id);
        }
        for (int i = txn.walEntries.size() - 1; i >= 0; i--) {
            restoreEntry(txn.walEntries.get(i));
        }
        releaseAllLocks(txn);
    }

    // === 事务生命周期 ===

    public void begin() {
        String session = requireSession();
        if (activeTxns.containsKey(session)) {
            throw new IllegalStateException("会话已有活跃事务，请先 COMMIT 或 ROLLBACK");
        }
        Transaction txn = new Transaction(nextTxnId++);
        activeTxns.put(session, txn);

        if (walEnabled) {
            try {
                txnOpenWal(txn, true);
                txnWriteLine(txn, WALEntry.begin(txn.id));
                txnFlushWal(txn);
            } catch (IOException e) {
                txnCloseWal(txn);
                activeTxns.remove(session);
                throw new RuntimeException("WAL 初始化失败，无法开始事务", e);
            }
        }
    }

    public void commit() {
        String session = requireSession();
        Transaction txn = currentTxn();
        if (txn == null) {
            throw new IllegalStateException("没有活跃事务");
        }

        if (walEnabled) {
            try {
                txnWriteLine(txn, WALEntry.commit(txn.id));
                txnFlushWal(txn);
            } catch (IOException e) {
                // 保留 WAL 文件（无 COMMIT 行），崩溃恢复时按未提交回滚
                txnCloseWal(txn);
                throw new RuntimeException("WAL 提交失败，事务未提交，请 ROLLBACK 后重试", e);
            }
            txnCloseWal(txn);
            deleteWALFile(txn.id);
        }

        releaseAllLocks(txn);
        activeTxns.remove(session);
    }

    public void rollback() {
        String session = requireSession();
        Transaction txn = currentTxn();
        if (txn == null) {
            throw new IllegalStateException("没有活跃事务");
        }

        notifyTxnAborted(txn);
        txnCloseWal(txn);
        if (walEnabled) {
            deleteWALFile(txn.id);
        }

        // 逆序恢复所有已应用的方块变更
        for (int i = txn.walEntries.size() - 1; i >= 0; i--) {
            restoreEntry(txn.walEntries.get(i));
        }

        releaseAllLocks(txn);
        activeTxns.remove(session);
    }

    // === 方块变更日志 ===

    /**
     * 记录一次方块变更，必须在变更落盘之前调用。
     * oldColor/newColor 为 DyeColor 枚举名，null 表示空气。
     * 无论 WAL 是否启用都写入内存列表（回滚需要）；WAL 启用时写入事务私有缓冲。
     * IO 失败直接抛出：调用方（BlockOperator → SqlExecutor）会中止并回滚当前事务。
     */
    public void logBlockChange(String tableName, int x, int y, int z, String oldColor, String newColor) {
        Transaction txn = currentTxn();
        if (txn == null) return;

        WALEntry entry = WALEntry.op(txn.id, tableName, x, y, z, oldColor, newColor);
        txn.walEntries.add(entry);
        if (walEnabled) {
            try {
                txnWriteLine(txn, entry);
            } catch (IOException e) {
                throw new RuntimeException("WAL 写入失败，中止当前事务", e);
            }
        }
    }

    /**
     * 把当前事务的 WAL 缓冲推到操作系统。方块批量写入（语句边界）后调用，
     * 保证已应用到世界的数据都有对应的持久日志。
     */
    public void flushWALBuffer() {
        Transaction txn = currentTxn();
        if (txn != null && txn.walWriter != null) {
            try {
                txn.walWriter.flush();
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
        Transaction txn = currentTxn();
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
        Transaction txn = currentTxn();
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
        Transaction txn = currentTxn();
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

    // === WAL 文件操作（挂在事务上：多个会话的事务可同时开启） ===

    private void txnOpenWal(Transaction txn, boolean truncate) throws IOException {
        txnCloseWal(txn);
        Path file = walDir.resolve("txn_" + txn.id + ".wal");
        txn.walWriter = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                truncate ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND);
    }

    private void txnWriteLine(Transaction txn, WALEntry entry) throws IOException {
        if (txn.walWriter == null) {
            txnOpenWal(txn, false);
        }
        txn.walWriter.write(gson.toJson(entry));
        txn.walWriter.write(System.lineSeparator());
    }

    private void txnFlushWal(Transaction txn) throws IOException {
        if (txn.walWriter != null) {
            txn.walWriter.flush();
        }
    }

    private void txnCloseWal(Transaction txn) {
        if (txn.walWriter != null) {
            try {
                txn.walWriter.close();
            } catch (IOException ignored) {}
            txn.walWriter = null;
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
        public BufferedWriter walWriter; // 事务私有 WAL 写入器

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
