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
 * <p>显示在准星下方、快捷栏上方的居中位置。样式是<b>细条 + 剩余秒数</b>：
 * 进度条从右往左退（剩余时间变短），上方文字显示还剩几秒，精确到 0.1 秒。</p>
 */
public class ChannelHud implements LayeredDraw.Layer {

    /** 细条：高度只有 3 像素，看起来像一条线而不是一块砖。 */
    private static final int BAR_WIDTH = 100;
    private static final int BAR_HEIGHT = 3;
    /** 进度条底边距屏幕底部的距离（避开快捷栏与物品名）。 */
    private static final int BOTTOM_MARGIN = 72;
    /** 文字与进度条之间的间距。 */
    private static final int TEXT_GAP = 7;

    private static final int COLOR_BACKDROP = 0xC8100A18;
    private static final int COLOR_TRACK = 0xFF2A1A38;
    private static final int COLOR_FILL_TOP = 0xFFE8CFFF;
    private static final int COLOR_FILL_BOTTOM = 0xFF7B2FD6;
    private static final int COLOR_TEXT = 0xFFE0C8FF;

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

        int used = Math.min(player.getTicksUsingItem(), duration);
        int remainingTicks = Math.max(0, duration - used);

        // 剩余比例：1.0 = 刚开始引导，0.0 = 引导完成
        float remaining = Mth.clamp((float) remainingTicks / (float) duration, 0.0F, 1.0F);

        int x = (graphics.guiWidth() - BAR_WIDTH) / 2;
        int y = graphics.guiHeight() - BOTTOM_MARGIN;

        // 细条：外描边 → 槽底 → 从右往左退的填充
        graphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, COLOR_BACKDROP);
        graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, COLOR_TRACK);
        int filled = Mth.ceil(BAR_WIDTH * remaining);
        if (filled > 0) {
            graphics.fillGradient(x, y, x + filled, y + BAR_HEIGHT,
                    COLOR_FILL_TOP, COLOR_FILL_BOTTOM);
        }

        // 剩余秒数：向上取到 0.1 秒，避免刚开始就显示 3.9 跳 3.8 的抖动感
        String seconds = String.format(java.util.Locale.ROOT, "%.1f",
                remainingTicks / 20.0F);
        Component label = Component.translatable("hud.enderportal.channeling", seconds);
        int textX = (graphics.guiWidth() - minecraft.font.width(label)) / 2;
        graphics.drawString(minecraft.font, label, textX, y - TEXT_GAP, COLOR_TEXT, true);
    }
}
