# 末影传送石 v1.13

> Minecraft **1.21.1** / NeoForge **21.1.x**
>
> 门面动画和传送延迟都改成和**原版下界门**一致。

## 1. 门面动画：直接用原版的下界门贴图

之前是脚本生成的紫色 32 帧动画，虽然也是动画，但和原版下界门不是一回事。
现在模型直接引用**原版的贴图**：

```json
"textures": {
  "particle": "minecraft:block/nether_portal",
  "portal": "minecraft:block/nether_portal"
}
```

于是动画完全等同原版：同一张 `nether_portal.png`（16×512，32 帧）、
同一份 `.mcmeta`（`{"animation": {}}`）、同一个播放节奏。

几何也保持原版下界门的做法 —— 厚度 4/16 的薄片（z 6→10），正反两面都贴图；
渲染类型 `translucent`，和原版 `ItemBlockRenderTypes` 里 `NETHER_PORTAL` 的注册一致
（我核对过：`RenderType.translucent()`）。

好处是不只是"看起来像"：原版改贴图、资源包换下界门外观时，这座门会跟着变。

> 门方块仍然保留 `facing` 属性，四个朝向各有模型旋转 —— 否则朝东西的门
> 会看到薄片横穿门洞。

## 2. 传送前有蓄力延迟，数值照抄原版

以前踏进门就立刻传送。现在照原版 `PortalProcessor` 的做法，要**先在门里站够时间**：

| 对象 | 需要的 tick | 相当于 | 来源 |
| --- | --- | --- | --- |
| 玩家（生存） | 80 | 4 秒 | 游戏规则 `playersNetherPortalDefaultDelay` |
| 玩家（创造/无敌） | 1 | 瞬间 | 游戏规则 `playersNetherPortalCreativeDelay` |
| 非玩家实体（生物、掉落物） | 0 | 立即 | 原版硬编码 |

离开门之后计时**每 tick 衰减 4**（原版 `decayTick`），减到 0 就当作没进过门。
所以"站一下又走开"不会攒进度，必须一口气站满。

**没有加自己的配置项，直接读同一批游戏规则。** 在服务器上把
`playersNetherPortalDefaultDelay` 改成别的值时，这座门的蓄力时间会跟着变 ——
这样才真的"和下界传送一致"，而不是抄了个 4 秒的数字。

## 3. 传送后的冷却也改成原版数值

顺带修正了一处不一致：以前是自己拍的 5 秒，原版其实是：

| 对象 | 冷却 |
| --- | --- |
| 玩家 | **10 tick**（0.5 秒，`Player#getDimensionChangingDelay`） |
| 非玩家 | **300 tick**（15 秒，`Entity` 里的硬编码） |

注意那个 300 是给非玩家的 —— 原版玩家传完只锁半秒。

配置项 `teleportDelaySeconds` 因此删掉了（它已经没意义，时长由原版规则决定）。

## 一个实现上的坑

判断"还在不在门里"**不能**依赖 `entityInside`：它只在实体**移动**时被调用
（`Entity#move` → `checkInsideBlocks`），玩家站着不动时可能整 tick 都不触发，
那样计时永远涨不上去、人被卡在门里出不来。

所以每个 tick 会自己拿实体的包围盒扫一遍脚下的方块，确认是不是还压着自家门面。
计时表还记着实体所在维度，避免每 tick 把所有维度问一遍。

## 升级说明

- 产物：`enderportal-1.13.jar`
- 大小：72,891 字节 / 48 个条目
- SHA256：`989e12e12469fb79fffff8608d42bd31a5a3323808e63f6fe94c45c46606df76`
- 需要：Minecraft 1.21.1 + NeoForge 21.1.x

自己生成的 `remote_portal.png` / `.mcmeta` 已删除，`tools/gen_textures.py` 不再生成它
（只留两张物品贴图）。

## 建议的验证方式

1. 走进门，**站住数 4 秒** → 应该在这时被送走；中途走开再回来，计时会重来；
2. 创造模式下走进去 → 几乎立刻传送（1 tick）；
3. 往门里丢个物品 → 立即穿过（非玩家过渡时间为 0）；
4. 和原版下界门并排放，对比门面动画的速度和观感。
