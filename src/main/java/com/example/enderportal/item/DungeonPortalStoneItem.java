package com.example.enderportal.item;

import com.example.enderportal.Config;
import com.example.enderportal.portal.PortalManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 地牢传送石：另一端开在附近的<b>地牢</b>里。
 *
 * <p>引导、冷却、耐久、失效规则都和 {@link PortalStoneItem} 一致，
 * 区别只在"另一端开在哪"：</p>
 * <ul>
 *   <li>搜索范围是玩家周围若干个已加载区块（{@code dungeonSearchChunks}），
 *       不用 256~2048 格那套远程搜索；</li>
 *   <li>目标必须是<b>地牢</b>（原版 monster room）—— 判定方式是"刷怪笼 + 圆石地板"，
 *       见 {@link com.example.enderportal.portal.DungeonFinder}；</li>
 *   <li>地牢那一端用紧凑结构（只有门框 + 门面），因为地牢房间塞不下高台和台阶。</li>
 * </ul>
 */
public class DungeonPortalStoneItem extends AbstractPortalStoneItem {

    public DungeonPortalStoneItem(Properties properties) {
        super(properties);
    }

    @Override
    protected PortalManager.Result openPortal(ServerLevel level, ServerPlayer player) {
        return PortalManager.openDungeonPortalPair(level, player);
    }

    @Override
    protected String failureKey(PortalManager.Result result) {
        return switch (result) {
            case NO_GROUND -> "message.enderportal.no_ground";
            case TOO_CLOSE -> "message.enderportal.too_close";
            case NO_DUNGEON -> "message.enderportal.no_dungeon";
            default -> "message.enderportal.no_target";
        };
    }

    @Override
    protected String descriptionKey() {
        return "tooltip.enderportal.dungeon_stone";
    }

    @Override
    protected String loreKey() {
        return "tooltip.enderportal.dungeon_stone.lore";
    }

    @Override
    protected void appendExtraTooltip(List<Component> tooltip) {
        tooltip.add(Component.translatable("tooltip.enderportal.dungeon_stone.trips", Config.ROUND_TRIPS.get())
                .withStyle(ChatFormatting.DARK_PURPLE));
        tooltip.add(Component.translatable("tooltip.enderportal.dungeon_stone.search",
                Config.DUNGEON_SEARCH_CHUNKS.get() * 16)
                .withStyle(ChatFormatting.DARK_PURPLE));
    }
}
