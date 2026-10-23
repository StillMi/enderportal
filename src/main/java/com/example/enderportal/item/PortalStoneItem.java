package com.example.enderportal.item;

import com.example.enderportal.Config;
import com.example.enderportal.portal.PortalManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 末影传送石。
 *
 * <p>长按右键<b>引导 4 秒</b>（可配置），松手或引导完成前切换物品都会中断、不消耗次数。
 * 引导成功后会在玩家<b>视线瞄准的位置</b>开一座「台阶 + 高台 + 门」的结构
 * （瞄准点悬空时会自动落到最近的地面上），
 * 并在一个<b>不同生物群系</b>的远处开另一座相连的门。</p>
 *
 * <p>引导进度条由客户端 {@code ChannelHud} 绘制，数据来自原版同步的
 * {@link LivingEntity#isUsingItem()} / {@link LivingEntity#getTicksUsingItem()}，
 * 不需要额外的网络包。</p>
 *
 * <p>耐久度就是"还能开几次门"：默认 3 点，每成功开一次门消耗 1 点，
 * 第 3 次用完后物品直接消失。引导失败不扣次数。冷却 1 分钟与耐久相互独立。</p>
 */
public class PortalStoneItem extends Item {

    /** 一块传送石最多能开几次门，用满后物品消失。 */
    public static final int MAX_USES = 3;

    public PortalStoneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

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

        PortalManager.Result result = PortalManager.openPortalPair(serverLevel, player);
        if (result != PortalManager.Result.OK) {
            player.displayClientMessage(failureMessage(result), true);
            return stack; // 失败不扣次数、不进冷却
        }

        int cooldownTicks = Config.COOLDOWN_SECONDS.get() * 20;
        if (cooldownTicks > 0) {
            player.getCooldowns().addCooldown(this, cooldownTicks);
        }

        level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1.0F, 1.0F);

        // 消耗一次"开门次数"；用满 3 次后物品在此处被销毁
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

    /** 开门失败时的提示，按原因分开，玩家才知道该往哪儿瞄。 */
    private static Component failureMessage(PortalManager.Result result) {
        String key = switch (result) {
            case NO_GROUND -> "message.enderportal.no_ground";
            case TOO_CLOSE -> "message.enderportal.too_close";
            default -> "message.enderportal.no_target";
        };
        return Component.translatable(key).withStyle(ChatFormatting.RED);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.enderportal.portal_stone").withStyle(ChatFormatting.GRAY));

        int maxDamage = stack.getMaxDamage();
        int left = Math.max(0, maxDamage - stack.getDamageValue());
        tooltip.add(Component.translatable("tooltip.enderportal.portal_stone.uses", left, maxDamage)
                .withStyle(ChatFormatting.DARK_PURPLE));
        tooltip.add(Component.translatable("tooltip.enderportal.portal_stone.trips", Config.ROUND_TRIPS.get())
                .withStyle(ChatFormatting.DARK_PURPLE));
        tooltip.add(Component.translatable("tooltip.enderportal.portal_stone.channel", Config.CHANNEL_SECONDS.get())
                .withStyle(ChatFormatting.DARK_AQUA));

        // 来源简介
        tooltip.add(Component.translatable("tooltip.enderportal.portal_stone.lore")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
