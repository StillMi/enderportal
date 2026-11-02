package com.example.enderportal.portal;

import com.example.enderportal.Config;
import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 传送冷却与提示节流。
 *
 * <h2>冷却直接写在原版字段上</h2>
 *
 * <p>不再自己维护一张表 —— 那样有两份状态，客户端和服务端、排队前后都可能不一致。
 * 现在用原版的 {@code Entity#setPortalCooldown(int)}：它写的就是
 * {@code entity.portalCooldown}，由 {@code Entity#processPortalCooldown()} 每 tick 递减，
 * 而 {@code setAsInsidePortal} 第一件事就是检查 {@link Entity#isOnPortalCooldown()}，
 * 冷却期间<b>原版直接拒绝登记</b>，连特效和蓄力都不会开始。</p>
 *
 * <p>时长取 {@link Config#TELEPORT_DELAY_SECONDS}（默认 5 秒）。原版玩家是 10 tick，
 * 但那是给跨维度用的；同维度挪位置时半秒就又能进，体感等于没有冷却。</p>
 *
 * <p>提示节流仍用一张小表：次数用完时玩家站在门里会每 tick 触发，不节流会刷屏。</p>
 */
public final class PortalCooldowns {

    /** 次数用完时重复提示的间隔（tick）。 */
    private static final int HINT_TICKS = 40;
    private static final int MAX_ENTRIES = 8192;

    private static final Map<UUID, Long> HINTS = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Long> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private PortalCooldowns() {
    }

    /** 配置里的冷却时长（tick）；0 表示退回原版玩家的 10 tick。 */
    private static int cooldownTicks() {
        int seconds = Math.max(0, Config.TELEPORT_DELAY_SECONDS.get());
        return seconds > 0 ? seconds * 20 : 10;
    }

    /** 还在冷却里吗 —— 直接问原版字段。 */
    public static boolean isOnCooldown(Entity entity) {
        return entity.isOnPortalCooldown();
    }

    /** 传送成功后开始冷却 —— 写原版字段，原版每 tick 自己递减。 */
    public static void markPassed(Entity entity) {
        entity.setPortalCooldown(cooldownTicks());
    }

    /** 还剩多少 tick，仅用于提示。 */
    public static int remainingTicks(Entity entity) {
        return Math.max(0, entity.getPortalCooldown());
    }

    /** 撤销冷却（排了队但最终没传成时用）。 */
    public static void releasePass(Entity entity) {
        entity.setPortalCooldown(0);
    }

    /** 提示节流：同一实体 {@link #HINT_TICKS} tick 内只提示一次。 */
    public static boolean tryHint(Entity entity) {
        long now = entity.level().getGameTime();
        Long last = HINTS.get(entity.getUUID());
        if (last != null && now - last < HINT_TICKS) {
            return false;
        }
        HINTS.put(entity.getUUID(), now);
        return true;
    }
}
