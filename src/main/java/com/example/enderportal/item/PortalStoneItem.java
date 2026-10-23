package com.example.enderportal.item;

import com.example.enderportal.Config;
import com.example.enderportal.portal.PortalManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 末影传送石：另一端开在远处<b>另一个生物群系</b>。
 *
 * <p>长按右键<b>引导 4 秒</b>（可配置），松手或引导完成前切换物品都会中断、不消耗次数。
 * 引导成功后会在玩家<b>视线瞄准的位置</b>开一座「台阶 + 高台 + 门」的结构
 * （瞄准点悬空时会自动落到最近的地面上），
 * 并在一个<b>不同生物群系</b>的远处开另一座相连的门。</p>
 *
 * <p>耐久度就是"还能开几次门"：默认 3 点，每成功开一次门消耗 1 点，
 * 第 3 次用完后物品直接消失。引导失败不扣次数。冷却 1 分钟与耐久相互独立。</p>
 */
public class PortalStoneItem extends AbstractPortalStoneItem {

    public PortalStoneItem(Properties properties) {
        super(properties);
    }

    @Override
    protected PortalManager.Result openPortal(ServerLevel level, ServerPlayer player) {
        return PortalManager.openPortalPair(level, player);
    }

    @Override
    protected String failureKey(PortalManager.Result result) {
        return switch (result) {
            case NO_GROUND -> "message.enderportal.no_ground";
            case TOO_CLOSE -> "message.enderportal.too_close";
            default -> "message.enderportal.no_target";
        };
    }

    @Override
    protected String descriptionKey() {
        return "tooltip.enderportal.portal_stone";
    }

    @Override
    protected String loreKey() {
        return "tooltip.enderportal.portal_stone.lore";
    }

    @Override
    protected void appendExtraTooltip(List<Component> tooltip) {
        tooltip.add(Component.translatable("tooltip.enderportal.portal_stone.trips", Config.ROUND_TRIPS.get())
                .withStyle(ChatFormatting.DARK_PURPLE));
    }
}
