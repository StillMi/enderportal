# 末影传送石 v1.16

上一版说「接回原版管线」是错的 —— 我加了一句客户端提前返回，把特效整条路切断，
同时自写冷却和原版冷却互相打架。这版把两件事都改成真正走原版。

## 1. 镜头不扭曲：客户端被一句 return 切断了

`entityInside` 开头有一句：

```java
if (level.isClientSide()) {
    return;   // ← 就是这句
}
```

原版 `NetherPortalBlock#entityInside` **不分端**，两侧都调
`entity.setAsInsidePortal(this, pos)`。这一步在**客户端**上建出 `portalProcess`，
`LocalPlayer#getActivePortalLocalTransition()` 才读得到 `CONFUSION`，
进而触发镜头扭曲和 `PORTAL_TRIGGER` 环境音。只在服务端登记，客户端永远是 null，
所以「延迟和特效都没有」。

现在客户端分支保留、并且只做登记（不掺任何服务端校验），服务端照旧走 `canAccept`。

已核实的链路：
- `Entity#move` → `tryCheckInsideBlocks()` → `checkInsideBlocks()` 没有端判断，客户端会跑；
- `checkInsideBlocks` 遍历包围盒逐格调 `blockstate.entityInside(...)`；
- `Portal.Transition.CONFUSION` 由 `LocalPlayer#handleConfusionTransitionEffect` 消费。

## 2. 没有内置冷却：三处问题一起修

**（a）冷却和原版字段是两份状态。** 之前自己维护 `Map<UUID, Stamps>`，和原版
`entity.portalCooldown` 各记各的。现在直接委托原版：

```java
isOnCooldown -> entity.isOnPortalCooldown()
markPassed   -> entity.setPortalCooldown(配置的 tick 数)
```

`processPortalCooldown()` 每 tick 自己递减，不用我们管。

**（b）冷却会被自己无限刷新。** 原版 `setAsInsidePortal` 第一句是
`if (isOnPortalCooldown()) setPortalCooldown();` —— 它把冷却**重置回**
`getDimensionChangingDelay()`（玩家 10 tick）。站在门里每 tick 被重置，
冷却刚减 1 就回到 10，永远走不完。现在在 `entityInside` 最前面加守卫：
冷却期间**两端都不登记**。

**（c）蓄力器有两套。** 自写的 `Map<UUID, Charge>` 计时器和原版 `PortalProcessor`
同时在算，而且自写那套还依赖 `ServerLevel.getEntity` 反查玩家（查不到）。
现在整张表删掉，`PortalTimers` 只剩一个纯函数 `transitionTicks`，
蓄力完全交给原版 `Entity#handlePortal`。

冷却时长仍是 `teleportDelaySeconds`（默认 5 秒），`0` 退回原版玩家的 10 tick。

## 产物

- enderportal-1.16.jar
- 70,954 字节 / 45 条目
- SHA256: 3fc25ef92dbd9dac89869974adecf59210432920e36c0d6428d1099e961d28c1

## 请按这个顺序验

1. **先只测特效**：站进门里不动，**应当立刻**听到 `PORTAL_TRIGGER` 的那声闷响、
   画面开始缓慢旋转变紫。这一步不通，后面的都不用看 —— 说明客户端登记还是没生效。
2. 站着不动数 4 秒，应被送走。
3. 送走后 5 秒内再站进门里：**不应**有特效、也不应被传送。
4. 等过 5 秒再进：特效恢复、再次传送。

如果第 1 步仍然不通，说明问题不在我的登记逻辑，而在模型/方块注册层面，
请把 `latest.log` 里带 `enderportal` 的行发我。
