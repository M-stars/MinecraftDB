package com.minecraftdb.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mod 配置管理（使用 JSON 文件，通过 Gson 读写）
 */
public class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // HTTP API
    public static boolean HTTP_API_ENABLED = true;
    public static int HTTP_API_PORT = 25566;
    public static String HTTP_API_BIND_ADDRESS = "127.0.0.1";
    public static String HTTP_API_TOKEN = "";
    public static boolean HTTP_API_ENABLE_CORS = false;

    // TCP CLI (Telnet 式 SQL 终端)
    public static boolean TCP_CLI_ENABLED = true;
    public static int TCP_CLI_PORT = 25567;
    public static String TCP_CLI_BIND_ADDRESS = "127.0.0.1";
    public static String TCP_CLI_TOKEN = "";

    // Database
    public static String DATABASE_DEFAULT_WORLD = "minecraftdb";
    public static boolean DATABASE_AUTO_CREATE_WORLD = true;
    public static boolean DATABASE_WAL_ENABLED = true;
    public static int DATABASE_CACHE_SIZE = 1000;

    // Performance
    public static int PERFORMANCE_MAX_SELECT_ROWS = 10000;
    public static boolean PERFORMANCE_BATCH_BLOCK_UPDATES = true;
    public static boolean PERFORMANCE_ASYNC_QUERIES = true;

    public static void loadConfig(Path path) {
        if (!Files.exists(path)) {
            saveConfig(path);
            return;
        }

        try {
            String json = Files.readString(path);
            ConfigData data = GSON.fromJson(json, ConfigData.class);
            if (data == null) return;

            if (data.http_api != null) {
                HTTP_API_ENABLED = data.http_api.enabled;
                HTTP_API_PORT = data.http_api.port;
                HTTP_API_BIND_ADDRESS = data.http_api.bind_address;
                HTTP_API_TOKEN = data.http_api.api_token != null ? data.http_api.api_token : "";
                HTTP_API_ENABLE_CORS = data.http_api.enable_cors;
            }
            if (data.tcp_cli != null) {
                TCP_CLI_ENABLED = data.tcp_cli.enabled;
                TCP_CLI_PORT = data.tcp_cli.port;
                TCP_CLI_BIND_ADDRESS = data.tcp_cli.bind_address != null ? data.tcp_cli.bind_address : "127.0.0.1";
                TCP_CLI_TOKEN = data.tcp_cli.token != null ? data.tcp_cli.token : "";
            }
            if (data.database != null) {
                DATABASE_DEFAULT_WORLD = data.database.default_world;
                DATABASE_AUTO_CREATE_WORLD = data.database.auto_create_world;
                DATABASE_WAL_ENABLED = data.database.wal_enabled;
                DATABASE_CACHE_SIZE = data.database.cache_size;
            }
            if (data.performance != null) {
                PERFORMANCE_MAX_SELECT_ROWS = data.performance.max_select_rows;
                PERFORMANCE_BATCH_BLOCK_UPDATES = data.performance.batch_block_updates;
                PERFORMANCE_ASYNC_QUERIES = data.performance.async_queries;
            }
        } catch (IOException e) {
            saveConfig(path);
        }
    }

    private static void saveConfig(Path path) {
        ConfigData data = new ConfigData();
        data.http_api = new HttpApiConfig();
        data.http_api.enabled = HTTP_API_ENABLED;
        data.http_api.port = HTTP_API_PORT;
        data.http_api.bind_address = HTTP_API_BIND_ADDRESS;
        data.http_api.api_token = HTTP_API_TOKEN;
        data.http_api.enable_cors = HTTP_API_ENABLE_CORS;

        data.tcp_cli = new TcpCliConfig();
        data.tcp_cli.enabled = TCP_CLI_ENABLED;
        data.tcp_cli.port = TCP_CLI_PORT;
        data.tcp_cli.bind_address = TCP_CLI_BIND_ADDRESS;
        data.tcp_cli.token = TCP_CLI_TOKEN;

        data.database = new DatabaseConfig();
        data.database.default_world = DATABASE_DEFAULT_WORLD;
        data.database.auto_create_world = DATABASE_AUTO_CREATE_WORLD;
        data.database.wal_enabled = DATABASE_WAL_ENABLED;
        data.database.cache_size = DATABASE_CACHE_SIZE;

        data.performance = new PerformanceConfig();
        data.performance.max_select_rows = PERFORMANCE_MAX_SELECT_ROWS;
        data.performance.batch_block_updates = PERFORMANCE_BATCH_BLOCK_UPDATES;
        data.performance.async_queries = PERFORMANCE_ASYNC_QUERIES;

        try {
            Files.createDirectories(path.getParent());
            String json = GSON.toJson(data);
            Files.writeString(path, json);
        } catch (IOException ignored) {}
    }

    // JSON 映射类

    private static class ConfigData {
        HttpApiConfig http_api;
        TcpCliConfig tcp_cli;
        DatabaseConfig database;
        PerformanceConfig performance;
    }

    private static class HttpApiConfig {
        boolean enabled;
        int port;
        String bind_address;
        String api_token;
        boolean enable_cors;
    }

    private static class TcpCliConfig {
        boolean enabled;
        int port;
        String bind_address;
        String token;
    }

    private static class DatabaseConfig {
        String default_world;
        boolean auto_create_world;
        boolean wal_enabled;
        int cache_size;
    }

    private static class PerformanceConfig {
        int max_select_rows;
        boolean batch_block_updates;
        boolean async_queries;
    }
}
