package com.example.enderportal.portal;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送冷却表：同一次穿门后 1 秒内不再重复触发，避免实体在门口被反复传送。
 */
public final class PortalCooldowns {

    private static final int COOLDOWN_TICKS = 20;
    private static final Map<UUID, Long> LAST_PASS = new ConcurrentHashMap<>();

    private PortalCooldowns() {
    }

    public static boolean tryPass(Entity entity) {
        long now = entity.level().getGameTime();
        Long last = LAST_PASS.get(entity.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS) {
            return false;
        }
        if (LAST_PASS.size() > 8192) {
            LAST_PASS.clear();
        }
        LAST_PASS.put(entity.getUUID(), now);
        return true;
    }
}
