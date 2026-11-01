package com.example.enderportal.client;

import com.example.enderportal.EnderPortal;
import com.example.enderportal.net.SetTargetPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 传送石的"目标坐标"输入界面。
 *
 * <p>手持传送石<b>潜行 + 右键</b>打开。三个输入框填 X / Y / Z，点"确定"就把坐标
 * 绑到手上那块石头上（发包给服务端，由服务端写进物品的数据组件）。</p>
 *
 * <p>预填顺序：这块石头已经绑过的坐标 → 玩家当前位置。所以想改一点点就微调数字，
 * 想绑到脚下就直接点确定。</p>
 *
 * <p>这个类只在客户端加载（{@link ClientHooks} 用 {@code Dist.CLIENT} 守着），
 * 服务端不会碰到它。</p>
 */
public class PortalTargetScreen extends Screen {

    private static final int ROW_HEIGHT = 24;

    private final InteractionHand hand;

    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;

    private PortalTargetScreen(InteractionHand hand) {
        super(Component.translatable("screen.enderportal.target.title"));
        this.hand = hand;
    }

    /** 打开界面的入口；由 {@link ClientHooks} 调用。 */
    public static void open(InteractionHand hand) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new PortalTargetScreen(hand));
    }

    @Override
    protected void init() {
        BlockPos initial = initialTarget();

        int centerX = this.width / 2;
        int boxWidth = 160;
        int left = centerX - boxWidth / 2;
        int top = this.height / 2 - ROW_HEIGHT * 2;

        this.xBox = addField(left, top, boxWidth, "X", initial.getX());
        this.yBox = addField(left, top + ROW_HEIGHT, boxWidth, "Y", initial.getY());
        this.zBox = addField(left, top + ROW_HEIGHT * 2, boxWidth, "Z", initial.getZ());

        // 回车直接提交，省得每次都去点按钮
        for (EditBox box : new EditBox[]{this.xBox, this.yBox, this.zBox}) {
            // 只允许数字和负号，顺手把非法输入挡住
            box.setFilter(text -> text.isEmpty() || text.equals("-") || text.matches("-?\\d{0,9}"));
        }

        int buttonY = top + ROW_HEIGHT * 3 + 8;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.enderportal.target.confirm"),
                        b -> submit())
                .bounds(centerX - 102, buttonY, 100, 20)
                .build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.enderportal.target.cancel"),
                        b -> this.onClose())
                .bounds(centerX + 2, buttonY, 100, 20)
                .build());
    }

    /** 预填值：已绑定的坐标优先，没绑过就用玩家当前位置。 */
    private BlockPos initialTarget() {
        ItemStack stack = net.minecraft.client.Minecraft.getInstance().player != null
                ? net.minecraft.client.Minecraft.getInstance().player.getItemInHand(this.hand)
                : ItemStack.EMPTY;

        BlockPos bound = stack.get(EnderPortal.PORTAL_TARGET.get());
        if (bound != null) {
            return bound;
        }
        var player = net.minecraft.client.Minecraft.getInstance().player;
        return player != null ? player.blockPosition() : BlockPos.ZERO;
    }

    private EditBox addField(int x, int y, int width, String label, int value) {
        EditBox box = new EditBox(this.font, x, y, width, 18,
                Component.translatable("screen.enderportal.target." + label.toLowerCase()));
        box.setMaxLength(12);
        box.setValue(Integer.toString(value));
        this.addRenderableWidget(box);
        return box;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - ROW_HEIGHT * 3 - 4, 0xFFFFFF);

        // 每个输入框左边标一下这是哪个轴
        int centerX = this.width / 2;
        int left = centerX - 160 / 2;
        int top = this.height / 2 - ROW_HEIGHT * 2;
        graphics.drawString(this.font, "X", left - 12, top + 6, 0xA0A0A0, false);
        graphics.drawString(this.font, "Y", left - 12, top + ROW_HEIGHT + 6, 0xA0A0A0, false);
        graphics.drawString(this.font, "Z", left - 12, top + ROW_HEIGHT * 2 + 6, 0xA0A0A0, false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 回车 = 确定，ESC 交给原版关闭逻辑。
        // 257 = GLFW_KEY_ENTER，335 = GLFW_KEY_KP_ENTER（小键盘回车）
        if (keyCode == 257 || keyCode == 335) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void submit() {
        int x = parseInt(this.xBox);
        int y = parseInt(this.yBox);
        int z = parseInt(this.zBox);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            // 有格子不是合法整数：留在界面上让玩家改，不关窗
            return;
        }
        PacketDistributor.sendToServer(new SetTargetPayload(new BlockPos(x, y, z)));
        this.onClose();
    }

    /** 解析失败返回 {@link Integer#MIN_VALUE} 当哨兵。 */
    private static int parseInt(EditBox box) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public boolean isPauseScreen() {
        // 单人游戏里不暂停，方便边看边填
        return false;
    }
}
