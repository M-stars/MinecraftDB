package com.minecraftdb;

import com.minecraftdb.api.HttpApiServer;
import com.minecraftdb.api.TcpCliServer;
import com.minecraftdb.command.CliSessionManager;
import com.minecraftdb.command.DatabaseCommand;
import com.minecraftdb.config.ModConfig;
import com.minecraftdb.database.DatabaseManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(MinecraftDB.MODID)
public class MinecraftDB {

    public static final String MODID = "minecraftdb";
    public static final Logger LOGGER = LogManager.getLogger("MinecraftDB");

    private static DatabaseManager databaseManager;
    private static HttpApiServer httpApiServer;
    private static TcpCliServer tcpCliServer;

    public MinecraftDB(FMLJavaModLoadingContext context) {
        IEventBus bus = context.getModEventBus();
        bus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ModConfig.loadConfig(FMLPaths.CONFIGDIR.get().resolve("minecraftdb.json"));
        LOGGER.info("MinecraftDB configuration loaded.");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        DatabaseCommand.register(event.getDispatcher());
        LOGGER.info("MinecraftDB commands registered.");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        databaseManager = new DatabaseManager(event.getServer());
        databaseManager.initialize();

        if (ModConfig.HTTP_API_ENABLED) {
            httpApiServer = new HttpApiServer(
                    ModConfig.HTTP_API_BIND_ADDRESS,
                    ModConfig.HTTP_API_PORT,
                    databaseManager
            );
            try {
                httpApiServer.start();
                LOGGER.info("HTTP API server started on {}:{}",
                        ModConfig.HTTP_API_BIND_ADDRESS,
                        ModConfig.HTTP_API_PORT);
            } catch (Exception e) {
                LOGGER.error("Failed to start HTTP API server", e);
            }
        }

        if (ModConfig.TCP_CLI_ENABLED) {
            tcpCliServer = new TcpCliServer(ModConfig.TCP_CLI_PORT, databaseManager);
            try {
                tcpCliServer.start();
                LOGGER.info("TCP CLI server started on port {} (telnet localhost {})",
                        ModConfig.TCP_CLI_PORT, ModConfig.TCP_CLI_PORT);
            } catch (Exception e) {
                LOGGER.error("Failed to start TCP CLI server", e);
            }
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        if (httpApiServer != null) {
            httpApiServer.stop();
            LOGGER.info("HTTP API server stopped.");
        }
        if (tcpCliServer != null) {
            tcpCliServer.stop();
            LOGGER.info("TCP CLI server stopped.");
        }
    }

    /**
     * CLI 模式：拦截聊天消息作为 SQL 执行
     */
    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player == null) return;
        if (!CliSessionManager.isInCli(player)) return;

        // 取消聊天消息（不发送到公共频道）
        if (event.isCancelable()) {
            event.setCanceled(true);
        }

        String message = event.getRawText();
        if (message == null || message.isBlank()) return;

        // 执行 SQL
        DatabaseCommand.handleCliInput(player, message);
    }

    public static DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}
