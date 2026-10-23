package com.example.enderportal.block;

import com.example.enderportal.EnderPortal;
import com.example.enderportal.portal.PortalCooldowns;
import com.example.enderportal.portal.PortalManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 传送门方块实体。
 *
 * <p>一座门由 2x3 = 6 个方块组成，每个方块各存一份相同的链接数据：
 * 自己这座门的原点与朝向、对面那座门的原点与朝向、以及剩余可用次数。
 * 这样即使区块被卸载再加载，传送门也依然能工作。</p>
 *
 * <p>失效有三条路径：次数用尽（{@link PortalManager#consumeUse}）、
 * 门面方块被破坏（{@link #onComponentBroken}）、
 * 黑曜石框架被破坏（{@link #serverTick} 里的每秒结构自检）。</p>
 */
public class RemotePortalBlockEntity extends BlockEntity {

    /** 结构自检间隔（tick）。 */
    private static final int CHECK_INTERVAL = 20;

    private boolean configured;
    private BlockPos frameOrigin = BlockPos.ZERO;
    private Direction frameFront = Direction.SOUTH;
    private BlockPos partnerOrigin = BlockPos.ZERO;
    private Direction partnerFront = Direction.SOUTH;
    private int remaining;

    /** 仅用于错开自检，不需要存档。 */
    private int tickCounter;

    public RemotePortalBlockEntity(BlockPos pos, BlockState state) {
        super(EnderPortal.REMOTE_PORTAL_BE.get(), pos, state);
    }

    public void configure(BlockPos frameOrigin, Direction frameFront,
                          BlockPos partnerOrigin, Direction partnerFront, int remaining) {
        this.frameOrigin = frameOrigin;
        this.frameFront = frameFront;
        this.partnerOrigin = partnerOrigin;
        this.partnerFront = partnerFront;
        this.remaining = remaining;
        this.configured = true;
        this.setChanged();
    }

    public int getRemaining() {
        return remaining;
    }

    public void setRemaining(int remaining) {
        this.remaining = remaining;
        this.setChanged();
    }

    public BlockPos getFrameOrigin() {
        return frameOrigin;
    }

    public Direction getFrameFront() {
        return frameFront;
    }

    public BlockPos getPartnerOrigin() {
        return partnerOrigin;
    }

    public Direction getPartnerFront() {
        return partnerFront;
    }

    /**
     * 服务端每 tick 调用，但只有"主方块"真正执行结构自检，其余方块直接返回。
     */
    public void serverTick(ServerLevel level, BlockPos pos) {
        if (!configured || remaining <= 0) {
            return;
        }
        if (!pos.equals(PortalManager.masterPos(frameOrigin, frameFront))) {
            return;
        }
        if (++tickCounter < CHECK_INTERVAL) {
            return;
        }
        tickCounter = 0;

        if (PortalManager.isTearingDown()) {
            return;
        }
        // 玩家把黑曜石框架拆了也算失效
        if (!PortalManager.isStructureIntact(level, frameOrigin, frameFront)) {
            invalidatePair(level);
        }
    }

    /**
     * 门面方块被破坏（挖掘 / 爆炸 / 活塞等）时调用，立即让整对门失效。
     */
    public void onComponentBroken(ServerLevel level, BlockPos brokenPos) {
        if (!configured) {
            return;
        }
        invalidatePair(level);
    }

    private void invalidatePair(ServerLevel level) {
        if (!configured) {
            return;
        }
        BlockPos frame = frameOrigin;
        Direction front = frameFront;
        BlockPos partner = partnerOrigin;
        Direction partnerDir = partnerFront;
        configured = false;
        PortalManager.invalidate(level, frame, front, partner, partnerDir);
    }

    /**
     * 实体踩进传送门方块时调用。
     */
    public void onEntityInside(ServerLevel level, Entity entity) {
        if (!configured || remaining <= 0) {
            return;
        }
        if (entity.isPassenger() || entity.isVehicle()) {
            return;
        }
        // 防止刚落地又被同一座门吞进去
        if (!PortalCooldowns.tryPass(entity)) {
            return;
        }

        // 落点：对面高台的正中央，玩家站在高台面上、面朝台阶（门外）
        double tx = PortalManager.localX(partnerOrigin, partnerFront, 2.0D, 1.5D);
        double ty = partnerOrigin.getY() + 1.0D;
        double tz = PortalManager.localZ(partnerOrigin, partnerFront, 2.0D, 1.5D);

        // 保证落点不是实心方块，避免卡墙/窒息
        PortalManager.clearLandingSpace(level, BlockPos.containing(tx, ty, tz));

        if (entity instanceof ServerPlayer serverPlayer) {
            serverPlayer.teleportTo(level, tx, ty, tz, partnerFront.toYRot(), 0.0F);
        } else {
            entity.teleportTo(tx, ty, tz);
        }
        entity.setDeltaMovement(Vec3.ZERO);
        entity.resetFallDistance();

        level.playSound(null, BlockPos.containing(tx, ty, tz), SoundEvents.PORTAL_TRAVEL,
                SoundSource.BLOCKS, 0.7F, 1.0F);
        level.sendParticles(ParticleTypes.PORTAL, tx, ty + 1.0D, tz, 32, 0.5D, 0.8D, 0.5D, 0.6D);

        int left = PortalManager.consumeUse(level, frameOrigin, frameFront, partnerOrigin, partnerFront);

        if (entity instanceof ServerPlayer player) {
            if (left <= 0) {
                player.displayClientMessage(Component.translatable("message.enderportal.expired")
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);
            } else {
                player.displayClientMessage(Component.translatable("message.enderportal.remaining", left)
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Configured", configured);
        tag.putLong("FrameOrigin", frameOrigin.asLong());
        tag.putString("FrameFront", frameFront.getName());
        tag.putLong("PartnerOrigin", partnerOrigin.asLong());
        tag.putString("PartnerFront", partnerFront.getName());
        tag.putInt("Remaining", remaining);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        configured = tag.getBoolean("Configured");
        frameOrigin = BlockPos.of(tag.getLong("FrameOrigin"));
        frameFront = readDirection(tag.getString("FrameFront"));
        partnerOrigin = BlockPos.of(tag.getLong("PartnerOrigin"));
        partnerFront = readDirection(tag.getString("PartnerFront"));
        remaining = tag.getInt("Remaining");
    }

    private static Direction readDirection(String name) {
        Direction direction = Direction.byName(name);
        return direction != null && direction.getAxis().isHorizontal() ? direction : Direction.SOUTH;
    }
}
