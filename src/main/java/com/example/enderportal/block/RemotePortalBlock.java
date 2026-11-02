package com.example.enderportal.block;

import com.example.enderportal.portal.PortalManager;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.core.Direction;
import org.slf4j.Logger;

/**
 * 传送门方块本体：无碰撞，实体站进去就会被传送。
 *
 * <p>门被拆除（玩家破坏、爆炸、活塞等）时立即让整对传送门失效；
 * 另外每秒做一次结构自检，用来发现黑曜石框架被拆掉的情况。</p>
 *
 * <p>方块带一个 {@link #FACING} 属性（门面朝向，也就是"门外"的方向）。
 * 门面用的是像下界门那样的<b>薄片</b>模型，薄片必须始终贴在门框平面上；
 * 没有这个属性的话，朝东/朝西的门会看到薄片横穿门洞。</p>
 */
public class RemotePortalBlock extends Block implements EntityBlock, Portal {

    /** 门面朝向（= 门外方向）。薄片模型按它旋转。 */
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private static final Logger LOGGER = LogUtils.getLogger();

    public RemotePortalBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.SOUTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
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

    /** 这座门现在愿不愿意接送这个实体（次数、冷却、骑乘都算）。 */
    private java.util.Optional<RemotePortalBlockEntity> portalAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof RemotePortalBlockEntity portal
                ? java.util.Optional.of(portal) : java.util.Optional.empty();
    }

    private boolean portalAllows(ServerLevel level, BlockPos pos, Entity entity) {
        return portalAt(level, pos).map(portal -> portal.canAccept(entity)).orElse(false);
    }

    @Override
    public int getPortalTransitionTime(ServerLevel level, Entity entity) {
        // 与下界门完全一致：读同一批游戏规则
        // （生存 80 tick / 创造 1 tick / 非玩家 0）
        return com.example.enderportal.portal.PortalTimers.transitionTicks(level, entity);
    }

    @Override
    public Portal.Transition getLocalTransition() {
        // 客户端据此渲染和下界门一样的 CONFUSION 屏幕特效
        return Portal.Transition.CONFUSION;
    }

    @Override
    public DimensionTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
        // 同维度内挪位置，交给方块实体在 tick 末尾执行（避开 handleMovePlayer 的位置纠偏）
        portalAt(level, pos).ifPresent(portal -> portal.requestTeleport(level, entity));
        return null;
    }

    /**
     * 实体压进门面方块时被调用 —— <b>两端都要跑</b>。
     *
     * <p>原版 {@code NetherPortalBlock#entityInside} 同样不分客户端/服务端，直接
     * {@code entity.setAsInsidePortal(this, pos)}。这一步在<b>客户端</b>上建出
     * {@code portalProcess}，{@code LocalPlayer#getActivePortalLocalTransition()}
     * 才能读到 {@link Portal.Transition#CONFUSION}，进而渲染镜头扭曲和
     * {@code PORTAL_TRIGGER} 环境音。<b>只在服务端登记，客户端永远没特效</b> ——
     * 之前这里有一句 {@code if (level.isClientSide()) return;}，就是特效不出现的原因。</p>
     *
     * <p>客户端的 {@code portalProcess} 只影响视觉；真正的传送判定、
     * 次数和冷却都在服务端做，客户端拦不拦都不影响结果。</p>
     */
    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        // 冷却期间一律不登记。
        //
        // 这一条必须放在最前面，而且两端都要查：原版 setAsInsidePortal 的第一句是
        // {@code if (isOnPortalCooldown()) setPortalCooldown();} —— 它会把冷却
        // <b>重置回 getDimensionChangingDelay()</b>（玩家 10 tick）。站在门里每 tick
        // 都被重置，冷却刚减 1 就被打回 10，永远走不完，看起来就是"冷却无限长"。
        if (entity.isOnPortalCooldown()) {
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            // 客户端：只为特效登记（CONFUSION + PORTAL_TRIGGER），不做任何服务端校验
            entity.setAsInsidePortal(this, pos);
            return;
        }
        // 服务端：次数用尽由 canAccept 拦掉，不登记就不会蓄力
        if (portalAllows(serverLevel, pos, entity)) {
            entity.setAsInsidePortal(this, pos);
        }
    }

    /**
     * 任何方式移除门方块都会走到这里（玩家挖掘、爆炸、活塞、/setblock 等）。
     * 只要不是我们自己拆的、也不是正在建门，整对传送门立刻失效。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide()
                && level instanceof ServerLevel serverLevel
                && !state.is(newState.getBlock())
                && !PortalManager.isTearingDown()
                && !PortalManager.isBuilding()) {
            // super.onRemove 会移除方块实体，所以必须先读
            if (level.getBlockEntity(pos) instanceof RemotePortalBlockEntity portal) {
                LOGGER.debug("[enderportal] portal block removed at {} -> {}, tearing down the pair", pos, newState);
                portal.onComponentBroken(serverLevel, pos);
            }
        } else if (!level.isClientSide() && PortalManager.isBuilding()) {
            LOGGER.debug("[enderportal] ignoring overwrite of {} while building", pos);
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
