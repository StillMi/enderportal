# 末影传送石 v1.17

## 第一次传送没有延迟：是创造模式的瞬传

「第一次传送延迟不生效」不是偶发，是**必然**——如果你在创造模式下测。

原版 `NetherPortalBlock#getPortalTransitionTime` 对**创造模式玩家**返回的是
`playersNetherPortalCreativeDelay`（默认 **1 tick**），因为原版下界门是跨维度传送，
给创造模式省时间。我上一版照抄了这条规则：

```java
if (player.getAbilities().invulnerable) {
    rule = GameRules.RULE_PLAYERS_NETHER_PORTAL_CREATIVE_DELAY;  // 1 tick = 瞬传
}
```

而测模组几乎一定在创造模式，所以第一脚踩进去就被瞬间送走，看起来就是「延迟没生效」。
生存模式下它其实是好的（80 tick = 4 秒），这也是为什么之前只在「第一次」出问题、
后面又「好像生效」——两边的模式不一样。

**这版改成创造和生存一律走 `playersNetherPortalDefaultDelay`（默认 80 tick = 4 秒）。**
理由：这座门不是跨维度，只是在同一世界内挪位置，没有「创造模式省得等」的必要。
非玩家实体（掉落物、生物）仍然保持原版的 0 tick 立即通过。

如果你确实想要创造瞬传，把游戏规则
`/gamerule playersNetherPortalDefaultDelay 1` 改小即可 —— 现在是**统一**读这一条。

## 顺带说明冷却的位置

原版 `handlePortal` 的顺序是：

```java
this.setPortalCooldown();                       // 先写冷却（10 tick）
DimensionTransition dt = process.getPortalDestination(...);   // 再拿目标
if (dt != null) { this.changeDimension(dt); }
```

也就是说，即使我们返回 `null`（自己排队传送），原版也已经写了一次 10 tick 冷却。
我们的 `executeTeleport` 在 tick 末尾成功后会按 `teleportDelaySeconds`（默认 5 秒）
**覆盖**这次写入，所以最终生效的是 5 秒 —— 顺序是对的，不需要额外处理。

## 产物

- enderportal-1.17.jar
- 70,838 字节 / 45 条目
- SHA256: 081328b7812d51acff86aee303ee9130d3296a4669b531ffda1caf571357176e

## 验的时候

1. 创造模式下站进门里，**应当数满 4 秒**才被送走（这版的重点）；
2. 传送后 5 秒内再进：没有特效、不传送；
3. 过 5 秒再进：恢复；
4. 想验证生存行为一致，切生存再走一次，时长应该一样。

如果创造模式下仍然瞬传，那说明 `getPortalTransitionTime` 没被调用到，
请把 `logs/latest.log` 发我 —— 但按这版的改动，它一定会是 80。
