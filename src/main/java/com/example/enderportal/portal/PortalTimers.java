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
     * 照抄 {@code NetherPortalBlock#getPortalTransitionTime}。
     *
     * <p>原来这里写过一个自维护的 {@code Map<UUID, Charge>} 计时器，
     * 但它在客户端不跑、又靠 {@code ServerLevel.getEntity} 反查玩家（查不到），
     * 结果是既不延迟也不传送。删掉之后只剩这一个纯函数。</p>
     */
    public static int transitionTicks(ServerLevel level, Entity entity) {
        if (entity instanceof Player player) {
            // 别写成三元表达式：两个分支的泛型不一样，会推断失败
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
}
