package com.example.enderportal.net;

import com.example.enderportal.EnderPortal;
import com.example.enderportal.item.AbstractPortalStoneItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：把手上那块传送石绑定的目标坐标改掉。
 *
 * <p>服务端不信任客户端送来的任何东西，所以这里重新校验：</p>
 * <ul>
 *   <li>玩家手上确实拿着一块传送石（防止空手或别的物品发这个包）；</li>
 *   <li>Y 在世界的可建范围内（防止把门建到虚空里）。</li>
 * </ul>
 *
 * <p>不带"哪只手"这个字段：客户端开界面时按的是某只手，但服务端自己重新找一遍更省事 ——
 * 先主手、再副手，谁拿着传送石就改谁。这样也少一个要编解码的字段。</p>
 */
public record SetTargetPayload(BlockPos target) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetTargetPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(EnderPortal.MODID, "set_target"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetTargetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetTargetPayload::target,
                    SetTargetPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端处理：校验 + 写入数据组件 + 反馈。 */
    public static void handle(SetTargetPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        // 方块和世界状态只在主线程可信，所以挪到主线程执行
        context.enqueueWork(() -> apply(player, payload));
    }

    private static void apply(ServerPlayer player, SetTargetPayload payload) {
        // 先主手再副手，找到拿着传送石的那只手
        InteractionHand hand = null;
        for (InteractionHand candidate : InteractionHand.values()) {
            if (player.getItemInHand(candidate).getItem() instanceof AbstractPortalStoneItem) {
                hand = candidate;
                break;
            }
        }
        if (hand == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        // 只有能设坐标的石头（末影传送石）才接受这个包
        if (!(stack.getItem() instanceof AbstractPortalStoneItem stone) || !stone.allowsBinding()) {
            return;
        }
        // 一块石头只能设定一次，不信任客户端、服务端再挡一遍
        if (AbstractPortalStoneItem.boundTarget(stack) != null) {
            player.displayClientMessage(
                    Component.translatable("message.enderportal.target_locked")
                            .withStyle(ChatFormatting.RED), true);
            return;
        }

        BlockPos target = payload.target();
        int minY = player.level().getMinBuildHeight();
        int maxY = player.level().getMaxBuildHeight() - 1;
        if (target.getY() < minY || target.getY() > maxY) {
            player.displayClientMessage(
                    Component.translatable("message.enderportal.target_out_of_range", minY, maxY)
                            .withStyle(ChatFormatting.RED), true);
            return;
        }

        stack.set(EnderPortal.PORTAL_TARGET.get(), target);
        player.displayClientMessage(
                Component.translatable("message.enderportal.target_set",
                        target.getX(), target.getY(), target.getZ())
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);
    }
}
