package com.example.enderportal.portal;

import com.example.enderportal.block.RemotePortalBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 待执行的传送队列。
 *
 * <h2>为什么不能就地传送</h2>
 *
 * <p>门方块的 {@code entityInside} 是在 {@link Entity#move} 内部被调用的
 * （{@code Entity#checkInsideBlocks}），而 {@code move()} 又是
 * {@code ServerGamePacketListenerImpl#handleMovePlayer} 在处理客户端移动包时调用的。
 * 那个方法在 {@code move()} <b>返回之后</b>还会做一次"位置纠偏"：</p>
 *
 * <pre>
 * this.player.move(...);                      // 我们在这里把人传送走了
 * d6 = d0 - this.player.getX();               // d0 是客户端上报的旧坐标
 * ...                                         // 于是位移差巨大 → "moved wrongly"
 * this.teleport(d3, d4, d5, ...);             // d3/d4/d5 是传送前的坐标 → 拉回去
 * </pre>
 *
 * <p>结果就是：传送被原地撤销，但次数已经扣掉了。多人游戏下客户端移动包更密集，
 * 几乎必然走这条路径，表现就是"一直传送失败、次数却被耗光"。</p>
 *
 * <p>所以这里只登记"想传送"，真正的传送放到 {@link ServerTickEvent.Post} 执行 ——
 * 那时已经脱离 {@code handleMovePlayer} 的调用栈，没有任何东西会把玩家拉回去。</p>
 *
 * <p>顺带解决另外两件事：</p>
 * <ul>
 *   <li>一座门有 6 个方块，同一次 {@code checkInsideBlocks} 会让 6 个方块实体各调一次
 *       {@code entityInside}；这里按实体去重，同 tick 只会排一次队；</li>
 *   <li>扣次数改到传送真正完成之后，传送没发生就不扣。</li>
 * </ul>
 */
public final class PortalTeleports {

    /** 一次待执行的传送。 */
    private record Pending(RemotePortalBlockEntity portal, ServerLevel level, Entity entity) {
    }

    private static final List<Pending> QUEUE = new ArrayList<>();
    private static final Set<UUID> QUEUED = new HashSet<>();

    private PortalTeleports() {
    }

    /** 这个实体本轮是不是已经排上队了（同一 tick 里 6 个门方块只排一次）。 */
    public static boolean isQueued(Entity entity) {
        return QUEUED.contains(entity.getUUID());
    }

    /** 登记一次传送；同一实体重复登记会被忽略。 */
    public static void enqueue(RemotePortalBlockEntity portal, ServerLevel level, Entity entity) {
        if (!QUEUED.add(entity.getUUID())) {
            return;
        }
        QUEUE.add(new Pending(portal, level, entity));
    }

    /**
     * tick 末尾统一执行。
     *
     * <p>先把队列整个换出来再逐个处理，这样执行过程中产生的新登记不会被这一轮吞掉，
     * 也不会因为遍历时修改列表而出问题。</p>
     */
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (QUEUE.isEmpty()) {
            return;
        }
        List<Pending> batch = new ArrayList<>(QUEUE);
        QUEUE.clear();
        QUEUED.clear();
        for (Pending pending : batch) {
            pending.portal().executeTeleport(pending.level(), pending.entity());
        }
    }
}
