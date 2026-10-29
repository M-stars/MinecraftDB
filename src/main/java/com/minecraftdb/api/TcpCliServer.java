package com.minecraftdb.api;

import com.minecraftdb.config.ModConfig;
import com.minecraftdb.database.DatabaseManager;
import com.minecraftdb.sql.SqlExecutor;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * TCP CLI 服务器 — 通过 telnet 连接直接输入 SQL。
 * 默认仅绑定 127.0.0.1；配置 TCP_CLI_TOKEN 后连接需先输入 auth <token> 才能执行 SQL。
 */
public class TcpCliServer {

    private final String bindAddress;
    private final int port;
    private final DatabaseManager dbManager;
    private ServerSocket serverSocket;
    private Thread listenerThread;
    private volatile boolean running;

    public TcpCliServer(String bindAddress, int port, DatabaseManager dbManager) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.dbManager = dbManager;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(bindAddress, port));
        running = true;

        listenerThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    Thread clientThread = new Thread(() -> handleClient(client));
                    clientThread.setDaemon(true);
                    clientThread.setName("MinecraftDB-CLI-" + client.getInetAddress());
                    clientThread.start();
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[MinecraftDB CLI] 连接错误: " + e.getMessage());
                    }
                }
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.setName("MinecraftDB-CLI-Listener");
        listenerThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
    }

    private void handleClient(Socket client) {
        try (
            InputStream is = client.getInputStream();
            OutputStream os = client.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true)
        ) {
            String dbName = dbManager.getDatabaseName();
            writer.println("");
            writer.println("╔══════════════════════════════════════════╗");
            writer.println("║      MinecraftDB 交互式 SQL 终端        ║");
            writer.println("╠══════════════════════════════════════════╣");
            writer.println("║  数据库: " + padRight(dbName, 31) + "║");
            writer.println("║  输入 SQL 执行，输入 exit 退出          ║");
            writer.println("║  help  查看帮助                         ║");
            writer.println("╚══════════════════════════════════════════╝");
            writer.println("");

            if (!authenticate(reader, writer)) {
                return;
            }

            while (running) {
                writer.print("db > ");
                writer.flush();

                String line = reader.readLine();
                if (line == null) break;

                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) {
                    writer.println("再见！");
                    break;
                }

                if (trimmed.equalsIgnoreCase("help") || trimmed.equals("?")) {
                    printHelp(writer);
                    continue;
                }

                if (trimmed.equalsIgnoreCase("tables")) {
                    executeAndPrint(writer, "SHOW TABLES");
                    continue;
                }

                if (trimmed.toLowerCase().startsWith("desc ")) {
                    executeAndPrint(writer, "DESC " + trimmed.substring(5).trim());
                    continue;
                }

                executeAndPrint(writer, trimmed);
            }
        } catch (IOException e) {
            // client disconnected
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 配置了 token 时，连接后必须先输入 auth <token>，最多尝试 3 次。
     */
    private boolean authenticate(BufferedReader reader, PrintWriter writer) throws IOException {
        String token = ModConfig.TCP_CLI_TOKEN;
        if (token == null || token.isEmpty()) return true;

        writer.println("此终端已启用认证，请输入: auth <token>");
        for (int attempt = 0; attempt < 3; attempt++) {
            writer.print("auth > ");
            writer.flush();
            String line = reader.readLine();
            if (line == null) return false;

            String trimmed = line.trim();
            if (trimmed.toLowerCase().startsWith("auth ")) {
                String candidate = trimmed.substring(5).trim();
                if (MessageDigest.isEqual(
                        candidate.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8))) {
                    writer.println("认证成功，欢迎！");
                    return true;
                }
            }
            writer.println("认证失败（剩余尝试次数: " + (2 - attempt) + "）");
        }
        writer.println("尝试次数过多，连接关闭。");
        return false;
    }

    private void executeAndPrint(PrintWriter writer, String sql) {
        try {
            // 经由 DatabaseManager 调度到服务端主线程执行（世界访问只允许主线程）
            SqlExecutor.QueryResult result = dbManager.executeSql(sql);

            if (result.isSuccess()) {
                writer.println(result.getMessage());
            } else {
                writer.println("错误: " + result.getMessage());
                return;
            }

            List<String> columns = result.getColumns();
            List<List<Object>> rows = result.getRows();

            if (columns.isEmpty() || rows.isEmpty()) {
                writer.println("");
                return;
            }

            // 计算列宽
            int[] widths = new int[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                widths[i] = columns.get(i).length();
            }
            for (List<Object> row : rows) {
                for (int i = 0; i < Math.min(row.size(), widths.length); i++) {
                    String val = row.get(i) != null ? row.get(i).toString() : "NULL";
                    if (val.length() > widths[i]) widths[i] = val.length();
                }
            }

            // 分隔线
            StringBuilder sep = new StringBuilder("+");
            for (int w : widths) sep.append("-".repeat(w + 2)).append("+");
            writer.println(sep);

            // 表头
            StringBuilder header = new StringBuilder("|");
            for (int i = 0; i < columns.size(); i++) {
                header.append(String.format(" %-" + widths[i] + "s |", columns.get(i)));
            }
            writer.println(header);
            writer.println(sep);

            // 数据行
            int maxRows = Math.min(rows.size(), 100);
            for (int r = 0; r < maxRows; r++) {
                List<Object> row = rows.get(r);
                StringBuilder line = new StringBuilder("|");
                for (int i = 0; i < columns.size(); i++) {
                    String val = (i < row.size() && row.get(i) != null) ? row.get(i).toString() : "NULL";
                    line.append(String.format(" %-" + widths[i] + "s |", val));
                }
                writer.println(line);
            }
            writer.println(sep);
            writer.println("共 " + result.getRowCount() + " 行");

            if (rows.size() > maxRows) {
                writer.println("(仅显示前 " + maxRows + " 行，剩余 " + (rows.size() - maxRows) + " 行)");
            }
            writer.println("");

        } catch (Exception e) {
            writer.println("SQL错误: " + e.getMessage());
            writer.println("");
        }
    }

    private void printHelp(PrintWriter writer) {
        writer.println("");
        writer.println("  MinecraftDB CLI 帮助");
        writer.println("  ────────────────────");
        writer.println("  直接输入 SQL 语句即可执行:");
        writer.println("");
        writer.println("  DDL (数据定义):");
        writer.println("    CREATE TABLE 表名 (字段 类型, ...)");
        writer.println("    DROP TABLE 表名");
        writer.println("    ALTER TABLE 表名 ADD 字段 类型");
        writer.println("    ALTER TABLE 表名 DROP 字段");
        writer.println("");
        writer.println("  DML (数据操作):");
        writer.println("    INSERT INTO 表名 (字段,...) VALUES (值,...)");
        writer.println("    SELECT * FROM 表名");
        writer.println("    SELECT * FROM 表名 WHERE 条件");
        writer.println("    UPDATE 表名 SET 字段=值 WHERE 条件");
        writer.println("    DELETE FROM 表名 WHERE 条件");
        writer.println("");
        writer.println("  事务:");
        writer.println("    BEGIN / COMMIT / ROLLBACK");
        writer.println("");
        writer.println("  快捷命令:");
        writer.println("    tables      显示所有表");
        writer.println("    desc <表名>  查看表结构");
        writer.println("    exit        退出");
        writer.println("");
    }

    private static String padRight(String s, int n) {
        if (s.length() >= n) return s.substring(0, n);
        return s + " ".repeat(n - s.length());
    }

    public int getPort() {
        return port;
    }
}
