package com.example.enderportal.portal;

import com.example.enderportal.Config;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送冷却表。
 *
 * <p>同一个实体传送完之后要隔 {@link Config#TELEPORT_DELAY_SECONDS} 秒才能再次被传送，
 * 否则站在门口会被来回反复传送（默认 5 秒）。</p>
 *
 * <p>另外还管一条"提示冷却"：个人次数用完后玩家站在门里会每 tick 触发一次判定，
 * 没有节流的话聊天栏/动作栏会被刷屏。</p>
 */
public final class PortalCooldowns {

    /** 次数用完时重复提示的间隔（tick）。 */
    private static final int HINT_TICKS = 40;
    private static final int MAX_ENTRIES = 8192;

    private static final Map<UUID, Long> LAST_PASS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_HINT = new ConcurrentHashMap<>();

    private PortalCooldowns() {
    }

    private static int delayTicks() {
        return Math.max(0, Config.TELEPORT_DELAY_SECONDS.get()) * 20;
    }

    /**
     * 记录一次传送并判断是否放行。
     *
     * @return {@code true} 表示可以传送（同时已经把时间戳记成"现在"）
     */
    public static boolean tryPass(Entity entity) {
        long now = entity.level().getGameTime();
        Long last = LAST_PASS.get(entity.getUUID());
        if (last != null && now - last < delayTicks()) {
            return false;
        }
        if (LAST_PASS.size() > MAX_ENTRIES) {
            LAST_PASS.clear();
        }
        LAST_PASS.put(entity.getUUID(), now);
        return true;
    }

    /** 玩家还有多久才能再次传送（tick），仅用于提示。 */
    public static int remainingTicks(Entity entity) {
        Long last = LAST_PASS.get(entity.getUUID());
        if (last == null) {
            return 0;
        }
        long left = delayTicks() - (entity.level().getGameTime() - last);
        return (int) Math.max(0, left);
    }

    /**
     * 提示节流：同一实体 {@link #HINT_TICKS} tick 内只提示一次。
     *
     * @return {@code true} 表示这次应该提示
     */
    public static boolean tryHint(Entity entity) {
        long now = entity.level().getGameTime();
        Long last = LAST_HINT.get(entity.getUUID());
        if (last != null && now - last < HINT_TICKS) {
            return false;
        }
        if (LAST_HINT.size() > MAX_ENTRIES) {
            LAST_HINT.clear();
        }
        LAST_HINT.put(entity.getUUID(), now);
        return true;
    }
}
