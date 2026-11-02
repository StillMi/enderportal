package com.example.enderportal.block;

import com.example.enderportal.EnderPortal;
import com.example.enderportal.portal.PortalCooldowns;
import com.example.enderportal.portal.PortalManager;
import com.example.enderportal.portal.PortalTeleports;
import com.example.enderportal.portal.PortalTimers;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 传送门方块实体。
 *
 * <p>一座门由 2x3 = 6 个方块组成，每个方块各存一份相同的链接数据：
 * 自己这座门的原点与朝向、对面那座门的原点与朝向，以及<b>按人分开</b>的剩余次数。
 * 这样即使区块被卸载再加载，传送门也依然能工作。</p>
 *
 * <h2>次数是怎么算的（v1.07 起）</h2>
 *
 * <p>次数是<b>每个人各算各的</b>：</p>
 * <ul>
 *   <li>开门的人（创造者）有自己的计数器，初始 {@link #quota} 次；</li>
 *   <li>其他每个玩家也各自有一份，从满额开始；</li>
 *   <li>创造者把自己的次数用完之后，<b>整座门失效</b>；</li>
 *   <li>其他玩家用完自己的次数后只是<b>自己不能再传送</b>，门仍然开着，
 *       他们的个人次数不会影响门的寿命。</li>
 * </ul>
 *
 * <p>非玩家实体（掉落物、生物）共用一个"公共桶"（{@link #SHARED_ACTOR}），
 * 这样既能让掉落物照常穿过，又不会因为物品无限循环而让计数表无限膨胀。</p>
 *
 * <p>失效有三条路径：创造者次数用尽（{@link PortalManager#consumeUse}）、
 * 门面方块被破坏（{@link #onComponentBroken}）、
 * 黑曜石框架被破坏（{@link #serverTick} 里的每秒结构自检）。</p>
 */
public class RemotePortalBlockEntity extends BlockEntity {

    /** 非玩家实体共用的计数桶。 */
    public static final UUID SHARED_ACTOR = new UUID(0L, 0L);

    /** 结构自检间隔（tick）。 */
    private static final int CHECK_INTERVAL = 20;
    /** 连续几次自检都不完整才判定失效（防止建门/区块加载瞬间的抖动误拆）。 */
    private static final int BROKEN_CHECKS_BEFORE_INVALIDATE = 2;
    private static final Logger LOGGER = LogUtils.getLogger();

    private boolean configured;
    private int layoutVersion;
    private boolean entryStep;
    private BlockPos frameOrigin = BlockPos.ZERO;
    private Direction frameFront = Direction.SOUTH;
    private BlockPos partnerOrigin = BlockPos.ZERO;
    private Direction partnerFront = Direction.SOUTH;

    /** 开门的人；决定这座门什么时候失效。 */
    private UUID creator = SHARED_ACTOR;
    /** 每个人（含创造者）的满额次数。 */
    private int quota;
    /** 创造者还剩几次。 */
    private int creatorLeft;
    /** 其他玩家 / 公共桶还剩几次；没有条目时视为满额。 */
    private final Map<UUID, Integer> othersLeft = new HashMap<>();

    /** 仅用于错开自检，不需要存档。 */
    private int tickCounter;
    /** 连续几次自检发现结构不完整；结构恢复完整就清零。 */
    private int brokenChecks;

    public RemotePortalBlockEntity(BlockPos pos, BlockState state) {
        super(EnderPortal.REMOTE_PORTAL_BE.get(), pos, state);
    }

    public void configure(BlockPos frameOrigin, Direction frameFront,
                          BlockPos partnerOrigin, Direction partnerFront,
                          UUID creator, int quota) {
        this.frameOrigin = frameOrigin;
        this.frameFront = frameFront;
        this.partnerOrigin = partnerOrigin;
        this.partnerFront = partnerFront;
        this.creator = creator;
        this.quota = quota;
        this.creatorLeft = quota;
        this.othersLeft.clear();
        this.configured = true;
        this.setChanged();
    }

    /** New geometry is persisted so older platforms still dismantle correctly. */
    public void setCompactLayout(boolean entryStep) {
        this.layoutVersion = 2;
        this.entryStep = entryStep;
        setChanged();
    }

    /** All six block entities of one entrance share this identity. */
    public boolean isSameEntrance(RemotePortalBlockEntity other) {
        return other != null && level == other.level
                && frameOrigin.equals(other.frameOrigin) && frameFront == other.frameFront
                && partnerOrigin.equals(other.partnerOrigin) && partnerFront == other.partnerFront
                && creator.equals(other.creator);
    }

    public boolean hasCompactLayout() { return layoutVersion >= 2; }
    public boolean hasEntryStep() { return entryStep; }

    /** 谁去用这座门，就按谁的 UUID 记账。 */
    public static UUID actorOf(Entity entity) {
        return entity instanceof ServerPlayer player ? player.getUUID() : SHARED_ACTOR;
    }

    public UUID getCreator() {
        return creator;
    }

    /** 某个使用者在还能传送几次。 */
    public int leftFor(UUID actor) {
        if (actor.equals(creator)) {
            return creatorLeft;
        }
        Integer left = othersLeft.get(actor);
        return left == null ? quota : left;
    }

    /** 记录某个使用者剩余次数；{@code 0} 会被明确写进表里，表示"已用完"。 */
    public void setLeftFor(UUID actor, int left) {
        if (actor.equals(creator)) {
            creatorLeft = left;
        } else {
            othersLeft.put(actor, left);
        }
        this.setChanged();
    }

    /** 把自己这一份计数复制给别人（用于两端同步）。 */
    public void copyUsageFrom(RemotePortalBlockEntity source) {
        this.creator = source.creator;
        this.quota = source.quota;
        this.creatorLeft = source.creatorLeft;
        this.othersLeft.clear();
        this.othersLeft.putAll(source.othersLeft);
        this.setChanged();
    }

    /**
     * 服务端每 tick 调用，但只有"主方块"真正执行结构自检，其余方块直接返回。
     */
    public void serverTick(ServerLevel level, BlockPos pos) {
        if (!configured) {
            return;
        }
        if (!pos.equals(PortalManager.masterPos(frameOrigin, frameFront))) {
            return;
        }
        if (++tickCounter < CHECK_INTERVAL) {
            return;
        }
        tickCounter = 0;

        if (PortalManager.isTearingDown() || PortalManager.isBuilding()) {
            brokenChecks = 0;
            return;
        }
        // 次数已经用尽的门不该继续立着。正常流程里 consumeUse 会在归零那次就把门拆掉，
        // 但旧存档迁移（v1.06 的 Remaining 已经是 0）会留下这种门：
        // 它既不能用、也不会自己消失，会永远杵在原地。这里补一次清理。
        if (creatorLeft <= 0) {
            LOGGER.debug("[enderportal] dropping an expired portal at {}", frameOrigin);
            invalidatePair(level);
            return;
        }
        // 玩家把黑曜石框架拆了也算失效；但要连续两次都不完整才拆，
        // 免得建门或区块刚加载时的瞬时状态把好好的门拆掉
        if (PortalManager.isStructureIntact(level, frameOrigin, frameFront)) {
            brokenChecks = 0;
            return;
        }
        if (++brokenChecks >= BROKEN_CHECKS_BEFORE_INVALIDATE) {
            LOGGER.debug("[enderportal] frame no longer intact at {}, tearing down the pair", frameOrigin);
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
     * 这座门现在能不能接这个实体。
     *
     * <p>只做判定、不改状态 —— {@link com.example.enderportal.block.RemotePortalBlock}
     * 用它决定要不要把实体登记进原版的 {@code PortalProcessor}。</p>
     */
    public boolean canAccept(Entity entity) {
        if (!configured || isRemoved()) {
            return false;
        }
        // 骑乘状态不传送，免得把坐骑和乘客拆开
        if (entity.isPassenger() || entity.isVehicle()) {
            return false;
        }
        UUID actor = actorOf(entity);
        if (leftFor(actor) <= 0) {
            if (entity instanceof ServerPlayer player && PortalCooldowns.tryHint(entity)) {
                player.displayClientMessage(Component.translatable("message.enderportal.player_exhausted")
                        .withStyle(ChatFormatting.GRAY), true);
            }
            return false;
        }
        return !PortalCooldowns.isOnCooldown(entity);
    }

    /**
     * 原版 {@code PortalProcessor} 蓄满后回调到这里，把传送排进 tick 末尾执行。
     *
     * <p>不在这里直接传送：{@code getPortalDestination} 是在实体 tick 的
     * {@code handlePortal} 里被调用的，而传送要脱离 {@code handleMovePlayer} 的调用栈，
     * 否则会被客户端上报的旧坐标纠偏拉回来、次数却已经扣掉。</p>
     */
    public void requestTeleport(ServerLevel level, Entity entity) {
        PortalTeleports.enqueue(this, level, entity);
    }

    /**
     * 真正执行传送。由 {@link PortalTeleports} 在 tick 末尾调用，
     * 此时已经脱离 {@code handleMovePlayer} 的调用栈。
     *
     * <p>蓄力期间不设冷却：冷却要等传送真正成功之后才记（{@link PortalCooldowns#markPassed}），
     * 否则"排了队又没送成"会让玩家白等一个冷却周期。时长按原版下界门给
     * （玩家 10 tick、非玩家 300 tick）。</p>
     */
    public void executeTeleport(ServerLevel level, Entity entity) {
        // isRemoved()：排队之后这座门可能已经被拆了（拆门时先 setRemoved 再清方块），
        // 这时 configured 还可能是 true（拆门走的是另一份方块实体），
        // 不加这道检查就会把人送到一座已经不存在的门上
        if (!configured || isRemoved() || entity.isRemoved() || !entity.isAlive()) {
            PortalTimers.reset(entity);
            return;
        }
        // 排队后实体可能已经被别处传走了（比如同一 tick 里进了另一座门），
        // 那就别把它再从新位置拽过来
        if (entity.level() != level) {
            PortalTimers.reset(entity);
            return;
        }
        // 排队之后可能被别的门先传走了、或者次数刚被用完，这里再确认一次
        UUID actor = actorOf(entity);
        if (leftFor(actor) <= 0) {
            PortalTimers.reset(entity);
            return;
        }

        // New arrivals stay over the inner half of the one-step entrance.
        // Legacy portals retain their wider platform spread.
        level.getChunk(partnerOrigin.getX() >> 4, partnerOrigin.getZ() >> 4);
        boolean compactTarget = false;
        for (BlockPos pos : PortalManager.portalBlocks(partnerOrigin, partnerFront)) {
            if (level.getBlockEntity(pos) instanceof RemotePortalBlockEntity other) {
                compactTarget = other.hasCompactLayout();
                break;
            }
        }
        double spreadX = ((entity.getUUID().hashCode() & 0xFF) / 255.0D - 0.5D)
                * (compactTarget ? 0.8D : 1.5D);
        double spreadZ = compactTarget ? 0.0D : (((entity.getUUID().hashCode() >>> 8) & 0xFF) / 255.0D - 0.5D) * 0.5D;
        double tx = PortalManager.localX(partnerOrigin, partnerFront, 2.0D + spreadX, 1.5D + spreadZ);
        double ty = partnerOrigin.getY() + 1.0D;
        double tz = PortalManager.localZ(partnerOrigin, partnerFront, 2.0D + spreadX, 1.5D + spreadZ);

        // 保证落点不是实心方块，避免卡墙/窒息
        PortalManager.clearLandingSpace(level, BlockPos.containing(tx, ty, tz));

        if (entity instanceof ServerPlayer serverPlayer) {
            serverPlayer.teleportTo(level, tx, ty, tz, partnerFront.toYRot(), 0.0F);
        } else {
            entity.teleportTo(tx, ty, tz);
        }
        entity.setDeltaMovement(Vec3.ZERO);
        entity.resetFallDistance();

        // 硬保险：位置没真的变过去，就绝不扣次数。
        // 这条路径以前出过事 —— 传送被 handleMovePlayer 的位置纠偏撤销，
        // 次数却已经扣了，玩家表现为"一直传送失败、次数却被耗光"。
        if (entity.position().distanceToSqr(tx, ty, tz) > 4.0D) {
            LOGGER.warn("[enderportal] teleport to {} did not take effect for {}, not consuming a use",
                    BlockPos.containing(tx, ty, tz), entity.getName().getString());
            PortalTimers.reset(entity);
            return;
        }

        // 传送真的成功了：清掉蓄力，并按原版下界门的时长开始冷却
        PortalTimers.reset(entity);
        PortalCooldowns.markPassed(entity);

        level.playSound(null, BlockPos.containing(tx, ty, tz), SoundEvents.PORTAL_TRAVEL,
                SoundSource.BLOCKS, 0.7F, 1.0F);
        level.sendParticles(ParticleTypes.PORTAL, tx, ty + 1.0D, tz, 32, 0.5D, 0.8D, 0.5D, 0.6D);

        // 记账：只有创造者的计数归零才会拆门
        int after = PortalManager.consumeUse(
                level, frameOrigin, frameFront, partnerOrigin, partnerFront, actor);

        if (entity instanceof ServerPlayer player) {
            if (after > 0) {
                player.displayClientMessage(Component.translatable("message.enderportal.remaining", after)
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);
            } else if (actor.equals(creator)) {
                player.displayClientMessage(Component.translatable("message.enderportal.expired")
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);
            } else {
                player.displayClientMessage(Component.translatable("message.enderportal.player_exhausted")
                        .withStyle(ChatFormatting.GRAY), true);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Configured", configured);
        tag.putInt("LayoutVersion", layoutVersion);
        tag.putBoolean("EntryStep", entryStep);
        tag.putLong("FrameOrigin", frameOrigin.asLong());
        tag.putString("FrameFront", frameFront.getName());
        tag.putLong("PartnerOrigin", partnerOrigin.asLong());
        tag.putString("PartnerFront", partnerFront.getName());
        tag.putUUID("Creator", creator);
        tag.putInt("Quota", quota);
        tag.putInt("CreatorLeft", creatorLeft);

        ListTag others = new ListTag();
        for (Map.Entry<UUID, Integer> entry : othersLeft.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", entry.getKey());
            one.putInt("Left", entry.getValue());
            others.add(one);
        }
        tag.put("OthersLeft", others);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        configured = tag.getBoolean("Configured");
        layoutVersion = tag.getInt("LayoutVersion");
        entryStep = tag.getBoolean("EntryStep");
        frameOrigin = BlockPos.of(tag.getLong("FrameOrigin"));
        frameFront = readDirection(tag.getString("FrameFront"));
        partnerOrigin = BlockPos.of(tag.getLong("PartnerOrigin"));
        partnerFront = readDirection(tag.getString("PartnerFront"));
        othersLeft.clear();

        if (tag.contains("CreatorLeft")) {
            creator = tag.contains("Creator") ? tag.getUUID("Creator") : SHARED_ACTOR;
            quota = Math.max(1, tag.getInt("Quota"));
            creatorLeft = tag.getInt("CreatorLeft");
            ListTag others = tag.getList("OthersLeft", Tag.TAG_COMPOUND);
            for (int i = 0; i < others.size(); i++) {
                CompoundTag one = others.getCompound(i);
                if (one.hasUUID("Id")) {
                    othersLeft.put(one.getUUID("Id"), one.getInt("Left"));
                }
            }
        } else if (tag.contains("Remaining")) {
            // v1.06 及更早的存档：整座门共用一个计数器。
            // 迁移成"创造者个人计数器"，读档后行为不变（只是其他玩家有了自己的配额）
            int legacy = Math.max(0, tag.getInt("Remaining"));
            creator = SHARED_ACTOR;
            quota = Math.max(1, legacy);
            creatorLeft = legacy;
        }
        // 注意：次数已经用尽的门在这里不能清 —— loadAdditional 拿不到 level。
        // 清理交给 serverTick，见那里的 creatorLeft <= 0 分支。
    }

    private static Direction readDirection(String name) {
        Direction direction = Direction.byName(name);
        return direction != null && direction.getAxis().isHorizontal() ? direction : Direction.SOUTH;
    }
}
