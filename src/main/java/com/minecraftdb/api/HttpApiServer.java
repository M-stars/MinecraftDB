package com.minecraftdb.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.minecraftdb.config.ModConfig;
import com.minecraftdb.database.DatabaseManager;
import com.minecraftdb.sql.SqlExecutor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * 内嵌 HTTP REST API 服务器（使用 JDK 内置 HttpServer，无需额外依赖）
 */
public class HttpApiServer {

    private final String bindAddress;
    private final int port;
    private final DatabaseManager dbManager;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private HttpServer server;

    public HttpApiServer(String bindAddress, int port, DatabaseManager dbManager) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.dbManager = dbManager;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);

        // 注册路由
        server.createContext("/api/v1/query", new QueryHandler());
        server.createContext("/api/v1/execute", new ExecuteHandler());
        server.createContext("/api/v1/tables", new TablesHandler());
        server.createContext("/api/v1/table/", new TableHandler());
        server.createContext("/api/v1/health", new HealthHandler());

        // 守护线程池：服务器关闭时不会阻塞 JVM 退出
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "MinecraftDB-HTTP");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // === 基础处理器 ===

    private abstract class BaseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                // CORS 头
                if (ModConfig.HTTP_API_ENABLE_CORS) {
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
                }

                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, 204, "{}");
                    return;
                }

                // 认证检查（常量时间比较，防时序侧信道）
                String token = ModConfig.HTTP_API_TOKEN;
                if (token != null && !token.isEmpty()) {
                    String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
                    String expected = "Bearer " + token;
                    boolean authorized = authHeader != null && MessageDigest.isEqual(
                            authHeader.getBytes(StandardCharsets.UTF_8),
                            expected.getBytes(StandardCharsets.UTF_8));
                    if (!authorized) {
                        sendJson(exchange, 401, Map.of("error", "Unauthorized"));
                        return;
                    }
                }

                handleRequest(exchange);
            } catch (PayloadTooLargeException e) {
                try {
                    sendJson(exchange, 413, Map.of("error", "Request body too large (max 1MB)"));
                } catch (IOException ignored) {}
            } catch (Exception e) {
                try {
                    sendJson(exchange, 500, Map.of("error", "Internal Server Error", "message", e.getMessage()));
                } catch (IOException ignored) {}
            } finally {
                exchange.close();
            }
        }

        protected abstract void handleRequest(HttpExchange exchange) throws IOException;
    }

    // === 路由处理器 ===

    /**
     * 会话 ID：客户端可用 X-MinecraftDB-Session 头在多个请求间维持同一事务；
     * 未提供时每个请求独立（自动生成一次性会话，等效自动提交）。
     */
    private String sessionId(HttpExchange exchange) {
        String s = exchange.getRequestHeaders().getFirst("X-MinecraftDB-Session");
        return "http:" + (s != null && !s.isEmpty() ? s : UUID.randomUUID());
    }

    private class QueryHandler extends BaseHandler {
        @Override
        protected void handleRequest(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);
            String sql = params.get("sql");

            if (sql == null || sql.isEmpty()) {
                sendJson(exchange, 400, Map.of("error", "Missing 'sql' query parameter"));
                return;
            }

            try {
                String decoded = URLDecoder.decode(sql, StandardCharsets.UTF_8);
                // GET 语义只读：写操作必须走 POST /execute，避免被预取/爬虫误触发
                String head = decoded.trim().toUpperCase(Locale.ROOT);
                if (!(head.startsWith("SELECT") || head.startsWith("SHOW") || head.startsWith("DESC"))) {
                    sendJson(exchange, 405, Map.of(
                            "error", "GET /query 仅允许只读语句（SELECT/SHOW/DESC），写操作请使用 POST /execute"));
                    return;
                }
                SqlExecutor.QueryResult result = dbManager.executeSql(sessionId(exchange), decoded);
                sendJson(exchange, 200, resultToMap(result));
            } catch (Exception e) {
                sendJson(exchange, 400, Map.of("error", "Query failed", "message", e.getMessage()));
            }
        }
    }

    private class ExecuteHandler extends BaseHandler {
        @Override
        protected void handleRequest(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            String body = readBody(exchange);
            if (body == null || body.isBlank()) {
                sendJson(exchange, 400, Map.of("error", "Request body is empty"));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> bodyMap = gson.fromJson(body, Map.class);
            if (bodyMap == null) {
                sendJson(exchange, 400, Map.of("error", "Invalid JSON body. Expected: {\"sql\": \"...\"}"));
                return;
            }

            String sql = (String) bodyMap.get("sql");
            if (sql == null || sql.isEmpty()) {
                sendJson(exchange, 400, Map.of("error", "Missing 'sql' field in request body"));
                return;
            }

            try {
                SqlExecutor.QueryResult result = dbManager.executeSql(sessionId(exchange), sql);
                sendJson(exchange, 200, resultToMap(result));
            } catch (Exception e) {
                sendJson(exchange, 400, Map.of("error", "Execute failed", "message", e.getMessage()));
            }
        }
    }

    private class TablesHandler extends BaseHandler {
        @Override
        protected void handleRequest(HttpExchange exchange) throws IOException {
            Set<String> tables = dbManager.getTableManager().listTables();
            sendJson(exchange, 200, Map.of(
                    "database", dbManager.getDatabaseName(),
                    "tables", new ArrayList<>(tables),
                    "count", tables.size()
            ));
        }
    }

    private class TableHandler extends BaseHandler {
        @Override
        protected void handleRequest(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);

            // 匹配 /api/v1/table/{name}/schema
            if (path.endsWith("/schema")) {
                String tableName = extractSegment(path, "/api/v1/table/", "/schema");
                handleSchema(exchange, tableName);
                return;
            }
            // 匹配 /api/v1/table/{name}/rows
            if (path.endsWith("/rows")) {
                String tableName = extractSegment(path, "/api/v1/table/", "/rows");
                int limit = parseInt(params, "limit", 100);
                int offset = parseInt(params, "offset", 0);
                handleRows(exchange, tableName, limit, offset);
                return;
            }

            sendJson(exchange, 404, Map.of("error", "Not Found"));
        }

        private void handleSchema(HttpExchange exchange, String tableName) throws IOException {
            List<com.minecraftdb.sql.ast.ColumnDefinition> schema = dbManager.getSchemaManager().getSchema(tableName);
            if (schema == null) {
                sendJson(exchange, 404, Map.of("error", "Table not found: " + tableName));
                return;
            }
            List<Map<String, String>> columns = new ArrayList<>();
            for (com.minecraftdb.sql.ast.ColumnDefinition cd : schema) {
                columns.add(Map.of(
                        "name", cd.name,
                        "type", cd.dataType,
                        "nullable", String.valueOf(cd.isNullable()),
                        "primary_key", String.valueOf(cd.isPrimaryKey())
                ));
            }
            sendJson(exchange, 200, Map.of("table", tableName, "columns", columns));
        }

        private void handleRows(HttpExchange exchange, String tableName, int limit, int offset) throws IOException {
            List<com.minecraftdb.sql.ast.ColumnDefinition> schema = dbManager.getSchemaManager().getSchema(tableName);
            if (schema == null) {
                sendJson(exchange, 404, Map.of("error", "Table not found: " + tableName));
                return;
            }
            // 分页下推为 z 范围：只扫描到取满一页为止，不再全表物化
            Map<String, Object> result = dbManager.callOnServerThread(() -> {
                com.minecraftdb.database.TableManager tm = dbManager.getTableManager();
                long maxZ = tm.getMaxZ(tableName);
                List<Map<String, Object>> page = new ArrayList<>();
                int skipped = 0;
                boolean hasMore = false;
                for (int z = 1; z < maxZ; z++) {
                    Map<String, Object> row = tm.readRow(tableName, schema, z);
                    if (row == null || row.isEmpty()) continue;
                    if (skipped < offset) {
                        skipped++;
                        continue;
                    }
                    if (page.size() < limit) {
                        page.add(row);
                    } else {
                        hasMore = true;
                        break;
                    }
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("table", tableName);
                out.put("rows", page);
                out.put("limit", limit);
                out.put("offset", offset);
                out.put("hasMore", hasMore);
                return out;
            });
            sendJson(exchange, 200, result);
        }

        private String extractSegment(String path, String prefix, String suffix) {
            String mid = path.substring(prefix.length());
            if (mid.endsWith(suffix)) {
                mid = mid.substring(0, mid.length() - suffix.length());
            }
            return mid;
        }
    }

    private class HealthHandler extends BaseHandler {
        @Override
        protected void handleRequest(HttpExchange exchange) throws IOException {
            sendJson(exchange, 200, Map.of("status", "ok", "database", dbManager.getDatabaseName()));
        }
    }

    // === 工具方法 ===

    private Map<String, Object> resultToMap(SqlExecutor.QueryResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", result.isSuccess());
        map.put("message", result.getMessage());
        map.put("rowCount", result.getRowCount());

        if (!result.getColumns().isEmpty()) {
            map.put("columns", result.getColumns());
            List<List<String>> serializableRows = new ArrayList<>();
            for (List<Object> row : result.getRows()) {
                List<String> sr = new ArrayList<>();
                for (Object val : row) {
                    sr.add(val != null ? val.toString() : null);
                }
                serializableRows.add(sr);
            }
            map.put("rows", serializableRows);
        }
        return map;
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object data) throws IOException {
        String json = gson.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return params;

        for (String pair : query.split("&")) {
            int eqIdx = pair.indexOf('=');
            if (eqIdx > 0) {
                try {
                    String key = URLDecoder.decode(pair.substring(0, eqIdx), StandardCharsets.UTF_8);
                    String val = URLDecoder.decode(pair.substring(eqIdx + 1), StandardCharsets.UTF_8);
                    params.put(key, val);
                } catch (Exception ignored) {}
            }
        }
        return params;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        String lenHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (lenHeader != null) {
            try {
                if (Long.parseLong(lenHeader.trim()) > MAX_BODY_BYTES) {
                    throw new PayloadTooLargeException();
                }
            } catch (NumberFormatException ignored) {}
        }
        try (InputStream is = exchange.getRequestBody()) {
            byte[] bytes = is.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new PayloadTooLargeException();
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private static class PayloadTooLargeException extends RuntimeException {}

    private int parseInt(Map<String, String> params, String key, int defaultVal) {
        try {
            return Integer.parseInt(params.getOrDefault(key, String.valueOf(defaultVal)));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
