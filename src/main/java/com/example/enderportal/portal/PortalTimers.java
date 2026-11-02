package com.example.enderportal.portal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;

/**
 * 进门蓄力时间的唯一来源，与下界门一致。
 *
 * <h2>为什么只剩这一个方法</h2>
 *
 * <p>蓄力本身已经交回原版：{@code Entity#handlePortal} 每 tick 推进
 * {@code PortalProcessor.portalTime}，够 {@code getPortalTransitionTime} 就传送。
 * 我们只要回答"这个实体要站多久"，所以这个类只保留
 * {@link #transitionTicks(ServerLevel, Entity)}。</p>
 *
 * <p>数值直接读原版游戏规则，不抄常量：</p>
 *
 * <table border="1">
 *   <caption>原版 {@code NetherPortalBlock#getPortalTransitionTime}</caption>
 *   <tr><th>对象</th><th>tick</th><th>规则</th></tr>
 *   <tr><td>玩家（生存）</td><td>80（4 秒）</td><td>{@code playersNetherPortalDefaultDelay}</td></tr>
 *   <tr><td>玩家（创造/无敌）</td><td>1</td><td>{@code playersNetherPortalCreativeDelay}</td></tr>
 *   <tr><td>非玩家实体</td><td>0（立即）</td><td>硬编码</td></tr>
 * </table>
 */
public final class PortalTimers {

    private PortalTimers() {
    }

    /**
     * 进门前要站多少 tick。
     *
     * <h2>和原版 deliberately 不同的一点</h2>
     *
     * <p>原版 {@code NetherPortalBlock#getPortalTransitionTime} 会给
     * <b>创造模式玩家</b>返回 {@code playersNetherPortalCreativeDelay}（默认 1 tick），
     * 也就是瞬间传送 —— 那是给"跨维度"省时间用的。</p>
     *
     * <p>这座门不是跨维度，只是在同一世界内挪位置。如果照抄那条规则，
     * 在创造模式下测就会看到"第一次传送没有延迟"——因为压根就是瞬传。
     * 所以这里<b>一律</b>使用 {@code playersNetherPortalDefaultDelay}
     * （默认 80 tick = 4 秒），创造和生存一致。</p>
     *
     * <p>非玩家实体仍然是 0（原版就是立即），掉落物和生物照常过门。</p>
     */
    public static int transitionTicks(ServerLevel level, Entity entity) {
        if (entity instanceof Player) {
            return Math.max(1, level.getGameRules()
                    .getInt(GameRules.RULE_PLAYERS_NETHER_PORTAL_DEFAULT_DELAY));
        }
        return 0;
    }
}
