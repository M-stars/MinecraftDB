package com.minecraftdb.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.DyeColor;

import java.util.Map;

/**
 * 世界操作工具类
 */
public final class WorldUtils {

    public static final int BEDROCK_Y = 0;
    public static final int DATA_START_Y = 1;
    public static final int DATA_MAX_Y = 255;

    // 16色羊毛 Block -> DyeColor 映射
    private static final Map<Block, DyeColor> WOOL_COLOR_MAP = Map.ofEntries(
        Map.entry(Blocks.WHITE_WOOL,      DyeColor.WHITE),
        Map.entry(Blocks.ORANGE_WOOL,     DyeColor.ORANGE),
        Map.entry(Blocks.MAGENTA_WOOL,    DyeColor.MAGENTA),
        Map.entry(Blocks.LIGHT_BLUE_WOOL, DyeColor.LIGHT_BLUE),
        Map.entry(Blocks.YELLOW_WOOL,     DyeColor.YELLOW),
        Map.entry(Blocks.LIME_WOOL,       DyeColor.LIME),
        Map.entry(Blocks.PINK_WOOL,       DyeColor.PINK),
        Map.entry(Blocks.GRAY_WOOL,       DyeColor.GRAY),
        Map.entry(Blocks.LIGHT_GRAY_WOOL, DyeColor.LIGHT_GRAY),
        Map.entry(Blocks.CYAN_WOOL,       DyeColor.CYAN),
        Map.entry(Blocks.PURPLE_WOOL,     DyeColor.PURPLE),
        Map.entry(Blocks.BLUE_WOOL,       DyeColor.BLUE),
        Map.entry(Blocks.BROWN_WOOL,      DyeColor.BROWN),
        Map.entry(Blocks.GREEN_WOOL,      DyeColor.GREEN),
        Map.entry(Blocks.RED_WOOL,        DyeColor.RED),
        Map.entry(Blocks.BLACK_WOOL,      DyeColor.BLACK)
    );

    private WorldUtils() {}

    public static BlockState woolState(DyeColor color) {
        Block block = switch (color) {
            case WHITE -> Blocks.WHITE_WOOL;
            case ORANGE -> Blocks.ORANGE_WOOL;
            case MAGENTA -> Blocks.MAGENTA_WOOL;
            case LIGHT_BLUE -> Blocks.LIGHT_BLUE_WOOL;
            case YELLOW -> Blocks.YELLOW_WOOL;
            case LIME -> Blocks.LIME_WOOL;
            case PINK -> Blocks.PINK_WOOL;
            case GRAY -> Blocks.GRAY_WOOL;
            case LIGHT_GRAY -> Blocks.LIGHT_GRAY_WOOL;
            case CYAN -> Blocks.CYAN_WOOL;
            case PURPLE -> Blocks.PURPLE_WOOL;
            case BLUE -> Blocks.BLUE_WOOL;
            case BROWN -> Blocks.BROWN_WOOL;
            case GREEN -> Blocks.GREEN_WOOL;
            case RED -> Blocks.RED_WOOL;
            case BLACK -> Blocks.BLACK_WOOL;
        };
        return block.defaultBlockState();
    }

    /**
     * 获取坐标处的羊毛颜色，如果不是羊毛则返回 null
     */
    public static DyeColor getWoolColor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return WOOL_COLOR_MAP.get(state.getBlock());
    }

    public static void setWool(ServerLevel level, BlockPos pos, DyeColor color) {
        BlockState state = woolState(color);
        // flag 2 = 同步客户端但跳过邻居形状更新：羊毛数据块不受邻居影响，省掉 6 方向更新开销
        level.setBlock(pos, state, 2);
    }

    public static void setAir(ServerLevel level, BlockPos pos) {
        level.removeBlock(pos, false);
    }

    public static void fillAreaAir(ServerLevel level, int xStart, int xEnd, int yStart, int yEnd, int zStart, int zEnd) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = xStart; x <= xEnd; x++) {
            for (int y = yStart; y <= yEnd; y++) {
                for (int z = zStart; z <= zEnd; z++) {
                    pos.set(x, y, z);
                    level.removeBlock(pos, false);
                }
            }
        }
    }
}
