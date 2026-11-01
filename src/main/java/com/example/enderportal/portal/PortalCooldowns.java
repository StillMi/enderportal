package com.example.enderportal.portal;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 传送<b>之后</b>的冷却，数值对齐原版下界门。
 *
 * <h2>原版怎么算的</h2>
 *
 * <p>传送完成时 {@code Entity#setPortalCooldown()} 会把 {@code portalCooldown} 设成
 * {@code getDimensionChangingDelay()}：</p>
 *
 * <table border="1">
 *   <caption>原版冷却时长</caption>
 *   <tr><th>对象</th><th>tick</th><th>来源</th></tr>
 *   <tr><td>玩家</td><td>10</td><td>{@code Player#getDimensionChangingDelay()}</td></tr>
 *   <tr><td>非玩家</td><td>300</td><td>{@code Entity} 里的硬编码</td></tr>
 * </table>
 *
 * <p>注意 300 那一档是给非玩家的；原版<b>玩家</b>传完只锁 10 tick（半秒）。
 * 之前这里是自己拍的 5 秒，和下界门不一致，现在按原版走。</p>
 *
 * <p>另外还管一条"提示冷却"：个人次数用完时玩家站在门里会每 tick 触发一次判定，
 * 没有节流的话动作栏会被刷屏。</p>
 *
 * <p>只在服务端主线程被调用，所以用普通 {@link Map}；跨线程没有任何收益，
 * 反而多一层内存屏障。</p>
 */
public final class PortalCooldowns {

    /** 原版玩家传送后的冷却（tick）。 */
    private static final int PLAYER_COOLDOWN_TICKS = 10;
    /** 原版非玩家实体传送后的冷却（tick）。 */
    private static final int OTHER_COOLDOWN_TICKS = 300;
    /** 次数用完时重复提示的间隔（tick）。 */
    private static final int HINT_TICKS = 40;
    private static final int MAX_ENTRIES = 8192;

    /** 同一实体两个计时戳，省掉两张表各自清理。 */
    private static final class Stamps {
        long pass = Long.MIN_VALUE;
        long hint = Long.MIN_VALUE;
    }

    /**
     * 冷却表，按<b>最久未使用</b>淘汰。
     *
     * <p>以前是"超过上限就整表 clear()"。多人服务器跑久了会撞上这个上限，
     * 而 clear() 的一瞬间<b>所有人的冷却和提示节流全部归零</b> ——
     * 正站在门口的人立刻又能被传送一次。单人几乎碰不到，多人跑一天就可能。</p>
     *
     * <p>现在用访问序的 {@link LinkedHashMap}：超过上限只淘汰最久没被碰过的那一条，
     * 其他人的计时戳不受影响。</p>
     */
    private static final Map<UUID, Stamps> STAMPS = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Stamps> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private PortalCooldowns() {
    }

    /**
     * 这个实体传送完要锁多少 tick —— 照抄原版 {@code getDimensionChangingDelay()}。
     *
     * <p>玩家是 10；非玩家是 300。骑在玩家身上的实体跟着乘客算（原版也是这么处理的）。</p>
     */
    private static int cooldownTicks(Entity entity) {
        if (entity instanceof Player) {
            return PLAYER_COOLDOWN_TICKS;
        }
        if (entity.getFirstPassenger() instanceof ServerPlayer) {
            return PLAYER_COOLDOWN_TICKS;
        }
        return OTHER_COOLDOWN_TICKS;
    }

    private static Stamps stampsOf(UUID id) {
        return STAMPS.computeIfAbsent(id, key -> new Stamps());
    }

    /** 还在冷却里吗（只读，不改状态）。 */
    public static boolean isOnCooldown(Entity entity) {
        Stamps stamps = STAMPS.get(entity.getUUID());
        if (stamps == null) {
            return false;
        }
        long elapsed = Stamp.elapsed(entity.level().getGameTime(), stamps.pass);
        return elapsed < cooldownTicks(entity);
    }

    /** 记一次传送，开始冷却。传送真正成功之后才调用。 */
    public static void markPassed(Entity entity) {
        stampsOf(entity.getUUID()).pass = entity.level().getGameTime();
    }

    /** 还剩多少 tick 才能再传，仅用于提示。 */
    public static int remainingTicks(Entity entity) {
        Stamps stamps = STAMPS.get(entity.getUUID());
        if (stamps == null) {
            return 0;
        }
        long elapsed = Stamp.elapsed(entity.level().getGameTime(), stamps.pass);
        return (int) Math.max(0, cooldownTicks(entity) - elapsed);
    }

    /**
     * 撤销一次冷却。
     *
     * <p>用于"已经排好队、但传送最终没发生"的情况（门被拆了、实体被别处传走等）。
     * 不撤销的话玩家会白等一个冷却周期却什么也没发生。</p>
     */
    public static void releasePass(Entity entity) {
        Stamps stamps = STAMPS.get(entity.getUUID());
        if (stamps != null) {
            stamps.pass = Long.MIN_VALUE;
        }
    }

    /**
     * 提示节流：同一实体 {@link #HINT_TICKS} tick 内只提示一次。
     *
     * @return {@code true} 表示这次应该提示
     */
    public static boolean tryHint(Entity entity) {
        long now = entity.level().getGameTime();
        Stamps stamps = stampsOf(entity.getUUID());
        if (Stamp.elapsed(now, stamps.hint) < HINT_TICKS) {
            return false;
        }
        stamps.hint = now;
        return true;
    }

    /** 时间戳运算：从未设过时返回一个足够大的数，让调用方自然放行。 */
    private static final class Stamp {
        private Stamp() {
        }

        static long elapsed(long now, long since) {
            if (since == Long.MIN_VALUE) {
                return Long.MAX_VALUE;
            }
            // 跨维或者 /time set 让 gameTime 回退过也不会算出负数
            return Math.max(0, now - since);
        }
    }
}
