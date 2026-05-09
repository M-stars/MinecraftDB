package com.minecraftdb.command;

import com.minecraftdb.MinecraftDB;
import com.minecraftdb.database.DatabaseManager;
import com.minecraftdb.sql.SqlExecutor.QueryResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class DatabaseCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("db");

        // /db cli — 进入交互式 CLI 模式
        root.then(Commands.literal("cli")
            .executes(DatabaseCommand::enterCli));

        // /db cli exit — 退出 CLI 模式
        root.then(Commands.literal("cli")
            .then(Commands.literal("exit")
                .executes(DatabaseCommand::exitCli)));

        // /db tables — 快捷命令：列出所有表
        root.then(Commands.literal("tables")
            .executes(ctx -> executeShortcut(ctx, "SHOW TABLES")));

        // /db desc <表名> — 快捷命令：查看表结构
        root.then(Commands.literal("desc")
            .then(Commands.argument("table", StringArgumentType.word())
                .executes(ctx -> {
                    String table = StringArgumentType.getString(ctx, "table");
                    return executeShortcut(ctx, "DESC " + table);
                })));

        // /db <SQL> — 执行任意 SQL
        root.then(Commands.argument("sql", StringArgumentType.greedyString())
            .executes(DatabaseCommand::executeSql));

        // /db — 帮助信息
        root.executes(ctx -> {
            DatabaseManager dbm = MinecraftDB.getDatabaseManager();
            String dbName = dbm != null ? dbm.getDatabaseName() : "未知";
            boolean inCli = ctx.getSource().getEntity() instanceof ServerPlayer sp
                && CliSessionManager.isInCli(sp);

            ctx.getSource().sendSuccess(() -> {
                MutableComponent msg = Component.literal("\n")
                    .append(Component.literal("=== MinecraftDB ===\n").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("数据库: " + dbName + "  ")
                        .withStyle(ChatFormatting.YELLOW));
                if (inCli) {
                    msg.append(Component.literal("[CLI模式已激活]\n").withStyle(ChatFormatting.GREEN));
                } else {
                    msg.append(Component.literal("\n"));
                }
                msg.append(Component.literal("\n命令:\n").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  /db <SQL>         执行SQL语句\n"))
                    .append(Component.literal("  /db cli           进入交互式CLI模式（直接输入SQL）\n"))
                    .append(Component.literal("  /db tables        列出所有表\n"))
                    .append(Component.literal("  /db desc <表名>   查看表结构\n"))
                    .append(Component.literal("\nCLI模式说明:\n").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  进入CLI模式后，直接在聊天栏输入SQL即可执行\n"))
                    .append(Component.literal("  输入 exit 退出CLI模式\n"))
                    .append(Component.literal("  输入 tables 查看所有表\n"))
                    .append(Component.literal("  输入 desc <表名> 查看表结构\n"));
                return msg;
            }, false);
            return 1;
        });

        dispatcher.register(root);
    }

    // === CLI 模式 ===

    private static int enterCli(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("只有玩家可以进入CLI模式"));
            return 0;
        }
        CliSessionManager.enter(player);
        player.sendSystemMessage(Component.literal("")
            .append(Component.literal("=== 进入 MinecraftDB CLI 模式 ===\n").withStyle(ChatFormatting.GREEN))
            .append(Component.literal("直接在聊天栏输入 SQL 语句即可执行\n").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("快捷命令: tables, desc <表名>, exit\n").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("输入 exit 退出CLI模式").withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    private static int exitCli(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
            CliSessionManager.exit(player);
            player.sendSystemMessage(Component.literal("已退出CLI模式").withStyle(ChatFormatting.YELLOW));
        }
        return 1;
    }

    // === 快捷命令 ===

    private static int executeShortcut(CommandContext<CommandSourceStack> ctx, String sql) {
        return executeSqlInternal(ctx.getSource(), sql);
    }

    // === SQL 执行 ===

    private static int executeSql(CommandContext<CommandSourceStack> ctx) {
        String sql = StringArgumentType.getString(ctx, "sql");
        return executeSqlInternal(ctx.getSource(), sql);
    }

    private static int executeSqlInternal(CommandSourceStack source, String sql) {
        DatabaseManager dbManager = MinecraftDB.getDatabaseManager();
        if (dbManager == null) {
            source.sendFailure(Component.literal("数据库尚未初始化，请等待服务器完全启动。"));
            return 0;
        }

        try {
            QueryResult result = dbManager.getSqlExecutor().execute(sql);

            if (result.isSuccess()) {
                Component msg = formatResult(dbManager.getDatabaseName(), result);
                source.sendSuccess(() -> msg, false);
                return 1;
            } else {
                source.sendFailure(
                    Component.literal("错误: " + result.getMessage()).withStyle(ChatFormatting.RED));
                return 0;
            }
        } catch (Exception e) {
            String errMsg = e.getMessage();
            if (errMsg == null) errMsg = e.getClass().getSimpleName();
            source.sendFailure(
                Component.literal("SQL错误: " + errMsg).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * 处理来自聊天事件的 CLI 输入（由 ChatHandler 调用）
     */
    public static void handleCliInput(ServerPlayer player, String input) {
        DatabaseManager dbManager = MinecraftDB.getDatabaseManager();
        if (dbManager == null) {
            player.sendSystemMessage(Component.literal("数据库尚未初始化"));
            return;
        }

        // 处理快捷命令
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) {
            CliSessionManager.exit(player);
            player.sendSystemMessage(Component.literal("已退出CLI模式").withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (trimmed.equalsIgnoreCase("tables")) {
            trimmed = "SHOW TABLES";
        } else if (trimmed.toLowerCase().startsWith("desc ")) {
            trimmed = "DESC " + trimmed.substring(5).trim();
        } else if (trimmed.equalsIgnoreCase("help") || trimmed.equals("?")) {
            player.sendSystemMessage(Component.literal("")
                .append(Component.literal("CLI快捷命令:\n").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  tables       = SHOW TABLES\n"))
                .append(Component.literal("  desc <表名>  = DESC <表名>\n"))
                .append(Component.literal("  exit         = 退出CLI模式\n"))
                .append(Component.literal("  help         = 显示此帮助\n"))
                .append(Component.literal("\n直接输入SQL语句即可执行").withStyle(ChatFormatting.GRAY)));
            return;
        }

        try {
            QueryResult result = dbManager.getSqlExecutor().execute(trimmed);
            if (result.isSuccess()) {
                player.sendSystemMessage(formatResult(dbManager.getDatabaseName(), result));
            } else {
                player.sendSystemMessage(
                    Component.literal("错误: " + result.getMessage()).withStyle(ChatFormatting.RED));
            }
        } catch (Exception e) {
            String errMsg = e.getMessage();
            if (errMsg == null) errMsg = e.getClass().getSimpleName();
            player.sendSystemMessage(
                Component.literal("SQL错误: " + errMsg).withStyle(ChatFormatting.RED));
        }
    }

    // === 格式化 ===

    private static Component formatResult(String dbName, QueryResult result) {
        MutableComponent header = Component.literal("[MinecraftDB:" + dbName + "] ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(result.getMessage() + "\n")
                        .withStyle(ChatFormatting.GREEN));

        if (result.getColumns().isEmpty() || result.getRows().isEmpty()) {
            return header;
        }

        StringBuilder sb = new StringBuilder();
        for (String col : result.getColumns()) {
            sb.append(String.format("%-20s", col.length() > 18 ? col.substring(0, 18) : col));
        }
        sb.append("\n");
        sb.append("-".repeat(Math.max(1, result.getColumns().size() * 20)));
        sb.append("\n");

        int maxDisplay = Math.min(result.getRowCount(), 50);
        for (int i = 0; i < maxDisplay; i++) {
            List<Object> row = result.getRows().get(i);
            for (Object val : row) {
                String strVal = val != null ? val.toString() : "NULL";
                if (strVal.length() > 18) strVal = strVal.substring(0, 18) + "..";
                sb.append(String.format("%-20s", strVal));
            }
            sb.append("\n");
        }

        if (result.getRowCount() > maxDisplay) {
            sb.append("... 还有 ").append(result.getRowCount() - maxDisplay).append(" 行\n");
        }

        return header.append(Component.literal(sb.toString()));
    }
}
