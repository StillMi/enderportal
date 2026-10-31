package com.example.enderportal.portal;

import com.example.enderportal.Config;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 传送冷却表。
 *
 * <p>同一个实体传送完之后要隔 {@link Config#TELEPORT_DELAY_SECONDS} 秒才能再次被传送，
 * 否则站在门口会被来回反复传送（默认 5 秒）。</p>
 *
 * <p>另外还管一条"提示冷却"：个人次数用完后玩家站在门里会每 tick 触发一次判定，
 * 没有节流的话聊天栏/动作栏会被刷屏。</p>
 *
 * <p>只在服务端主线程被调用，所以用普通 {@link HashMap}；跨线程没有任何收益，
 * 反而多一层内存屏障。</p>
 */
public final class PortalCooldowns {

    /** 次数用完时重复提示的间隔（tick）。 */
    private static final int HINT_TICKS = 40;
    private static final int MAX_ENTRIES = 8192;

    /** 同一实体两个计时戳，省掉两张表各自清理。 */
    private static final class Stamps {
        long pass = Long.MIN_VALUE;
        long hint = Long.MIN_VALUE;
    }

    private static final Map<UUID, Stamps> STAMPS = new HashMap<>();

    private PortalCooldowns() {
    }

    private static int delayTicks() {
        return Math.max(0, Config.TELEPORT_DELAY_SECONDS.get()) * 20;
    }

    private static Stamps stampsOf(UUID id) {
        if (STAMPS.size() > MAX_ENTRIES) {
            STAMPS.clear();
        }
        return STAMPS.computeIfAbsent(id, key -> new Stamps());
    }

    /**
     * 记录一次传送并判断是否放行。
     *
     * @return {@code true} 表示可以传送（同时已经把时间戳记成"现在"）
     */
    public static boolean tryPass(Entity entity) {
        int delay = delayTicks();
        long now = entity.level().getGameTime();
        Stamps stamps = stampsOf(entity.getUUID());
        // 延迟为 0 时等价于"每次都放行"，不需要比对时间
        if (delay > 0 && Stamp.elapsed(now, stamps.pass) < delay) {
            return false;
        }
        stamps.pass = now;
        return true;
    }

    /** 玩家还有多久才能再次传送（tick），仅用于提示。 */
    public static int remainingTicks(Entity entity) {
        int delay = delayTicks();
        if (delay <= 0) {
            return 0;
        }
        Stamps stamps = STAMPS.get(entity.getUUID());
        if (stamps == null) {
            return 0;
        }
        long elapsed = Stamp.elapsed(entity.level().getGameTime(), stamps.pass);
        return (int) Math.max(0, delay - elapsed);
    }

    /**
     * 撤销一次 {@link #tryPass} 写入的时间戳。
     *
     * <p>用于"已经放行、但传送最终没发生"的情况（排队后门失效、实体被别处传走等）。
     * 不撤销的话玩家会白白等满一个冷却周期，却什么也没发生。</p>
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
