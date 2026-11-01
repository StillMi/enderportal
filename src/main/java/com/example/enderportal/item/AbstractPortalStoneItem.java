package com.example.enderportal.item;

import com.example.enderportal.Config;
import com.example.enderportal.EnderPortal;
import com.example.enderportal.client.ClientHooks;
import com.example.enderportal.portal.PortalManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 传送石的公共逻辑：长按右键引导 → 引导成功后开一对门。
 *
 * <p>具体"门开在哪"由子类实现 {@link #openPortal} 决定：</p>
 * <ul>
 *   <li>{@link PortalStoneItem} —— 另一端在远处另一个群系</li>
 *   <li>{@link DungeonPortalStoneItem} —— 另一端在附近的地牢里</li>
 * </ul>
 *
 * <p>引导进度条由客户端 {@code ChannelHud} 绘制，数据来自原版同步的
 * {@link LivingEntity#isUsingItem()} / {@link LivingEntity#getTicksUsingItem()}，
 * 不需要额外的网络包。</p>
 *
 * <p>耐久度就是"还能开几次门"：每成功开一次门消耗 1 点，用完后物品直接消失。
 * 引导失败或中途松手不扣次数。冷却与耐久相互独立。</p>
 */
public abstract class AbstractPortalStoneItem extends Item {

    /** 一块传送石最多能开几次门，用满后物品消失。 */
    public static final int MAX_USES = 3;

    protected AbstractPortalStoneItem(Properties properties) {
        super(properties);
    }

    /**
     * 真正开门。只在服务端、引导完成时调用。
     *
     * @param target 这块石头绑定的目标坐标；{@code null} 表示没绑过，
     *               交给实现退回配置文件里的默认值
     * @return 开门结果；非 {@link PortalManager.Result#OK} 时不会扣次数
     */
    protected abstract PortalManager.Result openPortal(ServerLevel level, ServerPlayer player,
                                                       @Nullable BlockPos target);

    /** 失败提示的翻译键，按原因区分。 */
    protected abstract String failureKey(PortalManager.Result result);

    /** 物品说明第一行的翻译键。 */
    protected abstract String descriptionKey();

    /** 物品说明最后那行斜体来源文案的翻译键。 */
    protected abstract String loreKey();

    /** 子类可以往说明里再插几行（默认不插）。 */
    protected void appendExtraTooltip(List<Component> tooltip, ItemStack stack) {
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 潜行 + 右键 = 打开"目标坐标"界面，不进入引导。
        // 界面只在客户端开；服务端这边直接放行，等客户端发 SetTargetPayload 过来。
        // 这里用 FMLEnvironment.dist 显式判断，而不是只靠 level.isClientSide()：
        // ClientHooks 标了 @OnlyIn(CLIENT)，专用服务器上连"执行到这一行"都不要发生，
        // 否则类校验可能提前去加载一个只有客户端才有的类。
        if (player.isShiftKeyDown()) {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientHooks.openTargetScreen(hand);
            }
            return InteractionResultHolder.success(stack);
        }

        if (player.getCooldowns().isOnCooldown(this)) {
            if (!level.isClientSide()) {
                player.displayClientMessage(
                        Component.translatable("message.enderportal.cooldown").withStyle(ChatFormatting.RED), true);
            }
            return InteractionResultHolder.fail(stack);
        }

        // 开始引导：之后由原版每 tick 递减 useItemRemaining，走完调用 finishUsingItem
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    /**
     * 这块石头绑定的目标坐标；没绑过返回 {@code null}。
     *
     * <p>存在 ItemStack 的数据组件上，所以每块石头各记各的。</p>
     */
    @Nullable
    public static BlockPos boundTarget(ItemStack stack) {
        return stack.get(EnderPortal.PORTAL_TARGET.get());
    }

    /** 引导时长。原版用它作为"使用进度"的总量，进度条也按它算比例。 */
    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return Math.max(1, Config.CHANNEL_SECONDS.get()) * 20;
    }

    /** 不要吃东西/拉弓的手部动画，反馈交给进度条。 */
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    /**
     * 引导完成时由服务端调用（原版 {@code completeUsingItem} 只在服务端触发）。
     */
    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)
                || !(entity instanceof ServerPlayer player)) {
            return stack;
        }

        PortalManager.Result result = openPortal(serverLevel, player, boundTarget(stack));
        if (result != PortalManager.Result.OK) {
            player.displayClientMessage(
                    Component.translatable(failureKey(result)).withStyle(ChatFormatting.RED), true);
            return stack; // 失败不扣次数、不进冷却
        }

        int cooldownTicks = Config.COOLDOWN_SECONDS.get() * 20;
        if (cooldownTicks > 0) {
            player.getCooldowns().addCooldown(this, cooldownTicks);
        }

        level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1.0F, 1.0F);

        // 消耗一次"开门次数"；用满后物品在此处被销毁
        stack.hurtAndBreak(1, serverLevel, player, broken -> player.displayClientMessage(
                Component.translatable("message.enderportal.stone_exhausted")
                        .withStyle(ChatFormatting.DARK_PURPLE), true));

        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        int left = Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
        player.displayClientMessage(Component.translatable("message.enderportal.stone_uses_left", left)
                .withStyle(ChatFormatting.GRAY), true);

        return stack;
    }

    /** 引导中途松手：什么都不做（没有冷却、不扣次数）。 */
    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        // 有意留空
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(descriptionKey()).withStyle(ChatFormatting.GRAY));

        int maxDamage = stack.getMaxDamage();
        int left = Math.max(0, maxDamage - stack.getDamageValue());
        tooltip.add(Component.translatable("tooltip.enderportal.portal_stone.uses", left, maxDamage)
                .withStyle(ChatFormatting.DARK_PURPLE));

        appendExtraTooltip(tooltip, stack);

        tooltip.add(Component.translatable("tooltip.enderportal.portal_stone.channel", Config.CHANNEL_SECONDS.get())
                .withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable(loreKey())
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
