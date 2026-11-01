package com.example.enderportal.portal;

import com.example.enderportal.EnderPortal;
import com.example.enderportal.block.RemotePortalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 传送<b>前</b>的蓄力计时，语义对齐原版下界门。
 *
 * <h2>原版是怎么算的</h2>
 *
 * <p>原版走 {@code PortalProcessor}：站在门里时每 tick 把 {@code portalTime} 加一，
 * 加到 {@code getPortalTransitionTime} 就传送；离开门后每 tick <b>减 4</b>，
 * 减到 0 就当作没进过门。</p>
 *
 * <pre>
 * // PortalProcessor
 * if (!insidePortalThisTick) { portalTime = max(portalTime - 4, 0); return false; }
 * return canUse &amp;&amp; portalTime++ &gt;= portal.getPortalTransitionTime(level, entity);
 * </pre>
 *
 * <p>而 {@code getPortalTransitionTime}（{@code NetherPortalBlock}）给出的数值是：</p>
 *
 * <table border="1">
 *   <caption>原版过渡时间</caption>
 *   <tr><th>对象</th><th>tick</th><th>来源</th></tr>
 *   <tr><td>玩家（生存）</td><td>80（4 秒）</td>
 *       <td>游戏规则 {@code playersNetherPortalDefaultDelay}</td></tr>
 *   <tr><td>玩家（创造/无敌）</td><td>1</td>
 *       <td>游戏规则 {@code playersNetherPortalCreativeDelay}</td></tr>
 *   <tr><td>非玩家实体</td><td>0（立即）</td><td>硬编码</td></tr>
 * </table>
 *
 * <p>这里不加自己的配置项，直接读同一批游戏规则 —— 玩家在服务器上把
 * {@code playersNetherPortalDefaultDelay} 改成别的值时，这座门的蓄力时间会跟着变，
 * 这样才真的"和下界传送一致"。</p>
 *
 * <h2>为什么不靠 entityInside 判断"还在门里"</h2>
 *
 * <p>{@code entityInside} 只在实体<b>移动</b>时被调用（{@code Entity#move} →
 * {@code checkInsideBlocks}）。玩家站着不动时它可能整 tick 都不触发，
 * 那样计时就永远涨不上去、人也被卡在门里出不来。所以这里每个 tick 自己用包围盒
 * 扫一遍脚下的方块，判断是不是还站在自家门面里。</p>
 */
public final class PortalTimers {

    /** 离开门之后每 tick 衰减多少（原版 {@code PortalProcessor#decayTick}）。 */
    private static final int DECAY_PER_TICK = 4;

    /** 计时表上限，超过就整表丢弃（异常情况下别把内存吃光）。 */
    private static final int MAX_ENTRIES = 4096;

    /** 一个正在蓄力的实体。 */
    private static final class Charge {
        /** 已经站了多少 tick（原版 portalTime）。 */
        int portalTime;
        /** 它站的是哪座门 —— 换门要重新从 0 开始算。 */
        RemotePortalBlockEntity portal;
        /** 它所在的维度。存下来就不必每 tick 把所有维度都问一遍。 */
        ServerLevel level;
    }

    private static final Map<UUID, Charge> CHARGES = new HashMap<>();

    private PortalTimers() {
    }

    /**
     * 实体踩进自家门面时登记。
     *
     * <p>只登记"它想从这座门走"，<b>不</b>在这里传送 —— 传送由 {@link #tickAll} 在
     * 蓄力够了之后才排进 {@link PortalTeleports}。换了一座门就把计时清零。</p>
     */
    public static void enter(RemotePortalBlockEntity portal, Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (CHARGES.size() > MAX_ENTRIES) {
            CHARGES.clear();
        }
        Charge charge = CHARGES.computeIfAbsent(entity.getUUID(), key -> new Charge());
        if (!portal.isSameEntrance(charge.portal)) {
            charge.portal = portal;
            charge.portalTime = 0;
        }
        charge.portal = portal;
        charge.level = level;
    }

    /** 传送成功后清掉计时，免得刚落地又被累计的余量带走。 */
    public static void reset(Entity entity) {
        CHARGES.remove(entity.getUUID());
    }

    /** 实体死掉/下线时清理。 */
    public static void forget(Entity entity) {
        CHARGES.remove(entity.getUUID());
    }

    /**
     * 每个服务端 tick 推进一次所有蓄力中的实体。
     *
     * <p>必须在 {@link PortalTeleports} 排干队列<b>之前</b>调用，
     * 这样"这一 tick 蓄满"的实体能在同一 tick 里被送走，手感才和下界门一致。</p>
     */
    public static void tickAll(net.minecraft.server.MinecraftServer server) {
        if (CHARGES.isEmpty()) {
            return;
        }
        List<UUID> finished = new ArrayList<>();

        for (Map.Entry<UUID, Charge> entry : CHARGES.entrySet()) {
            UUID id = entry.getKey();
            Charge charge = entry.getValue();

            Entity entity = charge.level == null ? null : charge.level.getEntity(id);
            if (entity == null || entity.isRemoved() || !entity.isAlive()) {
                finished.add(id);
                continue;
            }
            ServerLevel level = charge.level;
            if (entity.level() != level) {
                // 换维度了：这一条的蓄力作废
                finished.add(id);
                continue;
            }

            if (!standingInOwnPortal(level, entity)) {
                // 原版：离开门就衰减，减到 0 视为没进过
                charge.portalTime = Math.max(charge.portalTime - DECAY_PER_TICK, 0);
                if (charge.portalTime <= 0) {
                    finished.add(id);
                }
                continue;
            }

            int transition = transitionTicks(level, entity);
            if (charge.portalTime >= transition) {
                // 原版是 portalTime++ >= transition（先比后加），这里保持一致：
                // 蓄够之后才排进队列，同一 tick 内由 PortalTeleports 执行
                PortalTeleports.enqueue(charge.portal, level, entity);
                charge.portalTime = 0;
            } else {
                charge.portalTime++;
            }
        }

        for (UUID id : finished) {
            CHARGES.remove(id);
        }
    }

    /**
     * 原版 {@code NetherPortalBlock#getPortalTransitionTime} 的等价实现。
     *
     * <p>直接读同一批游戏规则，玩家改规则时这座门跟着变。</p>
     */
    private static int transitionTicks(ServerLevel level, Entity entity) {
        if (entity instanceof Player player) {
            // 注意别写成三元表达式：两个分支的泛型不一样，会推断失败
            GameRules.Key<GameRules.IntegerValue> rule;
            if (player.getAbilities().invulnerable) {
                rule = GameRules.RULE_PLAYERS_NETHER_PORTAL_CREATIVE_DELAY;
            } else {
                rule = GameRules.RULE_PLAYERS_NETHER_PORTAL_DEFAULT_DELAY;
            }
            return Math.max(1, level.getGameRules().getInt(rule));
        }
        return 0;
    }

    /** 实体的包围盒是不是正压着自家的门面方块。 */
    private static boolean standingInOwnPortal(ServerLevel level, Entity entity) {
        AABB box = entity.getBoundingBox();
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.getBlockState(pos).is(EnderPortal.REMOTE_PORTAL.get())) {
                return true;
            }
        }
        return false;
    }

}
