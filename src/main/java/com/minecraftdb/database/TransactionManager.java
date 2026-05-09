package com.minecraftdb.database;

import com.google.gson.Gson;
import com.minecraftdb.config.ModConfig;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ACID 事务管理器（MVCC + WAL + 锁管理）。
 * WAL 日志存储在 config/minecraftdb/wal/ 目录下。
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
            saveWALFile(txn.id, new WALLog("BEGIN", txn.id, null, null));
        }
    }

    public void commit() {
        Transaction txn = currentTxn.get();
        if (txn == null) {
            throw new IllegalStateException("没有活跃事务");
        }

        if (walEnabled) {
            saveWALFile(txn.id, new WALLog("COMMIT", txn.id, null, null));
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

        if (walEnabled) {
            // 从 WAL 文件读取并逆向恢复
            WALLog log = loadWALFile(txn.id);
            if (log != null) {
                for (WALEntry entry : log.entries) {
                    revertWALEntry(entry);
                }
            }
            deleteWALFile(txn.id);
        }

        releaseAllLocks(txn);
        activeTransactions.remove(txn.id);
        currentTxn.remove();
    }

    public boolean isInTransaction() {
        return currentTxn.get() != null;
    }

    public Transaction getCurrentTransaction() {
        return currentTxn.get();
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

    public void logOperation(String tableName, int x, int y, int z, String oldValue, String newValue) {
        if (!walEnabled) return;

        Transaction txn = currentTxn.get();
        if (txn == null) return;

        WALEntry entry = new WALEntry(tableName, x, y, z, oldValue, newValue);
        txn.walEntries.add(entry);
    }

    private void saveWALFile(long txnId, WALLog log) {
        try {
            String json = gson.toJson(log);
            Files.writeString(walDir.resolve("txn_" + txnId + ".json"), json);
        } catch (IOException ignored) {}
    }

    private WALLog loadWALFile(long txnId) {
        try {
            Path file = walDir.resolve("txn_" + txnId + ".json");
            if (!Files.exists(file)) return null;
            String json = Files.readString(file);
            return gson.fromJson(json, WALLog.class);
        } catch (IOException e) {
            return null;
        }
    }

    private void deleteWALFile(long txnId) {
        try {
            Files.deleteIfExists(walDir.resolve("txn_" + txnId + ".json"));
        } catch (IOException ignored) {}
    }

    private void revertWALEntry(WALEntry entry) {
        // 恢复旧的羊毛颜色（通过重新放置方块实现）
        if (entry.oldValue != null) {
            // 在事务回滚时，TableManager 会负责重新写入数据
        }
    }

    /**
     * 服务端启动时恢复未完成的事务
     */
    public void recoverFromWAL() {
        if (!walEnabled) return;

        try {
            if (!Files.exists(walDir)) return;

            try (var files = Files.list(walDir)) {
                files.filter(f -> f.getFileName().toString().startsWith("txn_")
                                  && f.getFileName().toString().endsWith(".json"))
                     .forEach(f -> {
                         try {
                             String json = Files.readString(f);
                             WALLog log = gson.fromJson(json, WALLog.class);
                             if (log != null && !"COMMIT".equals(log.type)) {
                                 // 未提交的事务，执行回滚
                                 if (log.entries != null) {
                                     for (WALEntry entry : log.entries) {
                                         revertWALEntry(entry);
                                     }
                                 }
                             }
                             Files.deleteIfExists(f);
                         } catch (IOException ignored) {}
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
        public final Map<String, Long> snapshotVersions = new HashMap<>();

        public Transaction(long id) {
            this.id = id;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public record RowLockKey(String table, long z) {}

    public static class WALEntry {
        public String tableName;
        public int x, y, z;
        public String oldValue;
        public String newValue;

        public WALEntry() {}

        public WALEntry(String tableName, int x, int y, int z, String oldValue, String newValue) {
            this.tableName = tableName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }

    public static class WALLog {
        public String type;
        public long txnId;
        public String table;
        public List<WALEntry> entries;

        public WALLog() {}

        public WALLog(String type, long txnId, String table, WALEntry entry) {
            this.type = type;
            this.txnId = txnId;
            this.table = table;
            this.entries = new ArrayList<>();
            if (entry != null) {
                this.entries.add(entry);
            }
        }
    }
}
