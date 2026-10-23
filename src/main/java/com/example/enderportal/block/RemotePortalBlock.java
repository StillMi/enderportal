package com.example.enderportal.block;

import com.example.enderportal.portal.PortalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 传送门方块本体：无碰撞，实体站进去就会被传送。
 *
 * <p>门被拆除（玩家破坏、爆炸、活塞等）时立即让整对传送门失效；
 * 另外每秒做一次结构自检，用来发现黑曜石框架被拆掉的情况。</p>
 */
public class RemotePortalBlock extends Block implements EntityBlock {

    public RemotePortalBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RemotePortalBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return (lvl, pos, blockState, blockEntity) -> {
            if (blockEntity instanceof RemotePortalBlockEntity portal && lvl instanceof ServerLevel serverLevel) {
                portal.serverTick(serverLevel, pos);
            }
        };
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof RemotePortalBlockEntity portal) {
            portal.onEntityInside(serverLevel, entity);
        }
    }

    /**
     * 任何方式移除门方块都会走到这里（玩家挖掘、爆炸、活塞、/setblock 等）。
     * 只要不是我们自己拆的，整对传送门立刻失效。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide()
                && level instanceof ServerLevel serverLevel
                && !state.is(newState.getBlock())
                && !PortalManager.isTearingDown()) {
            // super.onRemove 会移除方块实体，所以必须先读
            if (level.getBlockEntity(pos) instanceof RemotePortalBlockEntity portal) {
                portal.onComponentBroken(serverLevel, pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(5) != 0) {
            return;
        }
        double x = pos.getX() + random.nextDouble();
        double y = pos.getY() + random.nextDouble();
        double z = pos.getZ() + random.nextDouble();
        level.addParticle(ParticleTypes.PORTAL, x, y, z,
                (random.nextDouble() - 0.5D) * 0.4D,
                (random.nextDouble() - 0.5D) * 0.4D,
                (random.nextDouble() - 0.5D) * 0.4D);
    }
}
