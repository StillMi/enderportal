# 末影传送石 v1.15

修掉主人报的三个问题。前两个是真的代码缺陷，第三个是构建内容正确、但旧 jar 没换掉。

## 1. 传送后冷却「失效」——两个 bug 叠在一起

**坐标换算错了。** `localX/localZ` 的连续坐标推导写成了
`0.5 + step * (lx - 0.5)`，而我们原点是门底梁左端那个**方块**，
正确形式是 `0.5 + step * lx`。朝北 / 朝西时 `step = -1`，落点整整偏出一格、
落到门背后 —— 玩家一站进新位置就又压在门面上，看起来就是「冷却没生效、一直被传」。

**蓄力计时把玩家丢了。** `PortalTimers` 每 tick 用 `level.getEntity(uuid)` 反查实体，
但玩家不在这张表里（`addPlayer` 走的是 `entityManager.addNewEntityWithoutEvent`），
查回来是 `null`，条目被当成「实体没了」直接删除。于是玩家永远蓄不满 —— 既不延迟也不传送。
现在直接持有实体引用，只在真的 `isRemoved()` / 死亡时才清。

**冷却时长也改回可配置。** 上版按原版 `Player#getDimensionChangingDelay()` 取了 10 tick，
但那是给跨维度用的；同维度挪位置时半秒就又能进，体感就是没冷却。
恢复 `teleportDelaySeconds`（默认 5 秒，0 表示用原版的 5 秒）。

## 2. 坐标只能由末影传送石设定，且只能设一次

- `AbstractPortalStoneItem#canBindTarget()` 默认 `false`，只有 `PortalStoneItem` 覆盖成 `true`；
- 地牢传送石潜行 + 右键会提示「这块石头不能设定坐标」，不会开界面；
- 已经绑过坐标的石头再潜行 + 右键会提示「已经绑定过坐标，不能再改」；
- 服务端 `SetTargetPayload` 再挡一遍（不信任客户端）：非末影石、或已有坐标，一律拒绝。

物品说明里的提示也改成「潜行 + 右键设定坐标（只能设定一次）」。

## 3. 下界门纹理、延迟与特效

**纹理其实一直是原版的。** 模型引用 `minecraft:block/nether_portal`，
和原版 `nether_portal_ns.json` 逐字对齐（同样的 z 6→10 薄片、双面、16×16 UV），
jar 里也确认没有残留自绘贴图。所以看到的不是下界门纹理，**最可能装的是旧 jar 或没重载资源**。
请确认 `mods/` 里只有 `enderportal-1.15.jar`，进游戏后按 `F3 + T` 重载一次。

**延迟与特效这次是真的接上了。** 上一版我用自写计时器，绕开了原版管线，
所以既没有 `CONFUSION` 屏幕特效，也没有 `PORTAL_TRIGGER` 环境音。现在：

- `RemotePortalBlock` 真正 `implements Portal`；
- `entityInside` 改为调用 `entity.setAsInsidePortal(this, pos)`，让原版建立 `PortalProcessor`；
- `getPortalTransitionTime` 直接复用 `PortalTimers.transitionTicks`，
  读的就是原版游戏规则（生存 80 tick / 创造 1 tick / 非玩家 0）；
- `getLocalTransition()` 返回 `Portal.Transition.CONFUSION` —— 客户端据此渲染
  和下界门一样的镜头旋转 + 环境音；
- `getPortalDestination` 不就地传送，只把传送排进 tick 末尾（避开位置纠偏），返回 `null`。

另外顺手修掉：生成门时清门洞只清局部 z=0，不再把门外那一格台阶清掉。

## 产物

- enderportal-1.15.jar
- 75,403 字节 / 48 条目
- SHA256: ec0f5fc22d94e0b0ecbba4551e34098c983e825ba7d4a64de16789d7d446ef4a

## 仍需你实测

编译与结构自检通过，但没有 Minecraft 客户端可跑。请重点看：

1. 站进门里 —— 应立刻出现下界门那种镜头扭曲、并响起环境音，4 秒后才传送；
2. 传送完 5 秒内再进门 —— 应被挡住并有冷却提示；
3. 末影石潜行右键设一次坐标，再潜行右键 —— 应提示不能再改；
4. 地牢石潜行右键 —— 应提示不能设定。
