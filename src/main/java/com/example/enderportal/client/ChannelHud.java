package com.example.enderportal.client;

import com.example.enderportal.item.AbstractPortalStoneItem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * 引导进度条（HUD）。
 *
 * <p>数据全部来自原版已经同步好的状态：{@link LocalPlayer#isUsingItem()}、
 * {@link LocalPlayer#getUseItem()}、{@link LocalPlayer#getTicksUsingItem()}，
 * 所以不需要自己发包，也不会出现客户端/服务端不同步。</p>
 *
 * <p>显示在准星下方、快捷栏上方的居中位置。</p>
 */
public class ChannelHud implements LayeredDraw.Layer {

    private static final int BAR_WIDTH = 110;
    private static final int BAR_HEIGHT = 8;
    /** 进度条底边距屏幕底部的距离（避开快捷栏与物品名）。 */
    private static final int BOTTOM_MARGIN = 72;

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        if (!player.isUsingItem()) {
            return;
        }

        ItemStack using = player.getUseItem();
        // 按类型判断，而不是按具体物品：以后再加传送石也不用改这里
        if (!(using.getItem() instanceof AbstractPortalStoneItem)) {
            return;
        }

        int duration = using.getUseDuration(player);
        if (duration <= 0) {
            return;
        }
        float progress = Mth.clamp((float) player.getTicksUsingItem() / (float) duration, 0.0F, 1.0F);

        int x = (graphics.guiWidth() - BAR_WIDTH) / 2;
        int y = graphics.guiHeight() - BOTTOM_MARGIN;

        // 外框 → 槽底 → 进度
        graphics.fill(x - 2, y - 2, x + BAR_WIDTH + 2, y + BAR_HEIGHT + 2, 0xC8100A18);
        graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0xFF2A1A38);
        int filled = Mth.ceil(BAR_WIDTH * progress);
        if (filled > 0) {
            graphics.fillGradient(x, y, x + filled, y + BAR_HEIGHT, 0xFFD9A6FF, 0xFF7B2FD6);
        }

        Component label = Component.translatable("hud.enderportal.channeling", Mth.floor(progress * 100.0F));
        int textX = (graphics.guiWidth() - minecraft.font.width(label)) / 2;
        graphics.drawString(minecraft.font, label, textX, y - 12, 0xFFE0C8FF, true);
    }
}
