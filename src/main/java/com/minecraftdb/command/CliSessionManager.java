package com.minecraftdb.command;

import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 管理交互式 CLI 会话。
 * 进入 CLI 模式后，玩家在聊天栏输入的文本直接作为 SQL 执行。
 */
public class CliSessionManager {

    private static final Set<UUID> cliSessions = Collections.synchronizedSet(new HashSet<>());

    public static boolean enter(ServerPlayer player) {
        return cliSessions.add(player.getUUID());
    }

    public static boolean exit(ServerPlayer player) {
        return cliSessions.remove(player.getUUID());
    }

    public static boolean isInCli(ServerPlayer player) {
        return cliSessions.contains(player.getUUID());
    }
}
