# 末影传送石 (Ender Portal Stone) v1.10

一个 Minecraft **1.21.1 / NeoForge 21.1.x** 小模组：用 8 块黑曜石 + 1 颗末影珍珠合成一颗"石头"，
**长按右键引导 4 秒**（屏幕下方有进度条）后，在**你鼠标瞄准的位置**与**另一个生物群系**之间
打开一对相通的「台阶 + 高台 + 传送门」，**每人**往返 2 次后自动失效，每次使用有 1 分钟冷却。
一块传送石最多只能开门 **3 次**，用完即毁；每次新开门，上一座还没失效的门会自动消散。

> **v1.10 修复**（多人一起玩时另外四处会出事的地方）：
>
> 1. **建门会把旁边的玩家埋进黑曜石里** —— "太近"判定原来只看开门那个人自己，
>    别人站在那儿完全不拦。现在结构范围内的所有玩家都算一遍。顺带修了反向的坑：
>    门面方块是无碰撞的，把它也算成"挡住"会导致门口有人时门一直开不出来。
> 2. **多人服务器跑久了所有人冷却被清零** —— 冷却表超过 8192 条时原来整表 `clear()`，
>    清空瞬间所有人的传送冷却一起归零。现在按最久未使用淘汰。
> 3. **两个人同时穿门落在同一坐标** —— 落点写死在高台正中央，撞在一起会被挤下高台。
>    现在按实体 UUID 做确定性偏移，在台面上摊开。
> 4. **门被拆掉后排队中的传送还会执行** —— 拆门清的是另一个方块实体实例，
>    队列里那个 `configured` 还是 `true`，会把人送到已经不存在的门上。
>
> **v1.09 修复**（多人游戏下传送失败却照样扣次数）：
>
> 1. **传送不再被"位置纠偏"撤销** —— 门方块的 `entityInside` 是被 `Entity.move()` 调用的，
>    而 `move()` 又在 `handleMovePlayer` 的调用栈里。那个方法在 `move()` 返回后会拿客户端上报的
>    **旧坐标**做一次纠偏，把刚传送走的玩家又拉回来。现在 `entityInside` 只登记，
>    真正的传送排到 `ServerTickEvent.Post` 执行，那时已经脱离那个调用栈。
> 2. **扣次数改到传送真正成功之后** —— 传送后检查坐标是否真的落在目标点，没落过去就不扣次数，
>    并回滚冷却时间戳，不让玩家白等。以前"传送失败但次数照扣"会累积成把门耗光。
> 3. **落点区块先显式加载** —— 多人时另一端常常是未加载状态，以前 `getBlockState` 会在
>    传送关键路径上做隐式同步加载。
>
> 这个问题单人很难复现：多人下客户端移动包更密集，几乎必然走那条会撤销传送的路径。
>
> **v1.08 内部整改**（玩法没变，改的是会伤到存档和卡顿的地方）：
>
> 1. **不再拿过期坐标拆方块** —— 新开门时会让上一座门失效，而注册表里的旧条目在门自然
>    失效后不会立刻清掉。以前是不管还在不在都拆，等于拿一段过期坐标把 4×5 范围内**所有
>    黑曜石和磨制黑石台阶**（包括玩家自己搭的）清成空气。现在先确认结构真的还在，
>    不在就只清记录、一根方块都不碰。
> 2. **建门失败只拆建成的那一端** —— 以前无差别拆两端，没建成那一端的坐标上可能是玩家的方块。
> 3. **落点清理只清自己的方块** —— 传送落点会把卡人的方块清成空气，现在只清黑曜石 / 台阶 /
>    门面这些自己放的，以及草、雪、花这类本来就可替换的；带方块实体的（刷怪笼、箱子）永不动。
> 4. **找远端群系不再一次生成 6 个区块** —— 上限降到 3，并在完整生成前先用已有高度图粗筛，
>    满 2048 格开一次门不会再卡好几秒。
> 5. **瞄准判定更快** —— 找落点时会反复探测同一批列和高度，现在整轮共用一个按需填充的缓存。
> 6. **旧存档的"僵尸门"会被清掉** —— 旧版迁移出来的、次数已归零却既不消失也不能用的门，
>    读档后会被移除。
>
> **v1.07 变更**：
>
> 1. **传送后 5 秒冷却** —— 传送完要等 5 秒才能再次被传送，避免在门口反复弹
>    （`teleportDelaySeconds` 可配置，动作栏会显示剩余秒数）。
> 2. **次数改成按人算** —— 每个玩家各自有 2 次往返。创造者用完后整座门失效；
>    其他玩家用完后只是自己不能再传送，**门继续开着**，不会因为别人蹭门而缩短寿命。
>
> **v1.06 修复**（重要）：
>
> 1. **地牢传送门"生成后立刻消失"** —— 根因是覆盖方块时 `onRemove` 把"被覆盖"当成
>    "玩家拆门"，于是整对门在建成的同一 tick 里被自己拆掉。现在建门期间屏蔽该判定，
>    并且落点会避开与另一端结构重叠。
> 2. **"太近 / 站不住脚"刷屏** —— 原来拿整个占地范围（含四周留白）去卡，
>    现在只挡真正会压到玩家的格子；占地范围允许个别列没有天然地面；
>    瞄准点实在放不下会自动退回"玩家正前方"再试，而不是直接报错。
>
> **v1.05 修复**：地牢传送石**没有引导进度条**（HUD 里写死了只认末影传送石，
> 现在改成按类型判断）；合成表改成 **8 块金锭 + 1 颗末影珍珠**。
>
> **v1.04 新增**：第二件物品 **地牢传送石**（紫色宝石 + 一圈火焰）。
> 引导 / 冷却 / 次数 / 失效规则和末影传送石完全一样，只是另一端不再跑几百上千格去找别的群系，
> 而是**在你附近找一个真正的地牢（原版刷怪房）**，把门开在地牢房间里。
>
> **v1.03 变更**：门不再固定生成在"玩家正前方"，而是生成在**视线瞄准的方块**上；
> 瞄准点悬空（树冠、半空方块、悬崖侧壁）时会自动吸附到最近的**能站人的地面**，
> 不会把门架在半空。

---

## 0. 两件物品

| | 末影传送石 | 地牢传送石 |
| --- | --- | --- |
| 图标 | 灰紫色石头 + 末影珍珠 | **紫色宝石 + 一圈火焰** |
| 合成 | 8 黑曜石 + 1 末影珍珠 | **8 金锭 + 1 末影珍珠** |
| 另一端 | 256~2048 格外**另一个群系** | 附近**地牢**里（默认半径 20 区块 = 320 格） |
| 地牢那一端结构 | 完整「台阶 + 高台 + 门」 | **紧凑版**：门框直接嵌进地牢的墙（房间只有 3 格高，塞不下高台） |
| 稀有度 | RARE | EPIC |
| 引导 / 冷却 / 次数 | 4 秒 / 60 秒 / 3 次 | 完全相同 |

---

## 1. 玩法

| 项目 | 数值 |
| --- | --- |
| 合成（末影传送石） | 3×3 工作台：8 块黑曜石围一圈，中间 1 颗末影珍珠 |
| 合成（地牢传送石） | 3×3 工作台：8 块金锭围一圈，中间 1 颗末影珍珠 |
| **引导时间** | **4 秒**（可配置），长按右键，中途松手即中断、不消耗次数 |
| 引导进度条 | 屏幕下方居中显示，带百分比；数据来自原版同步状态，无需额外网络包 |
| **开门位置** | **鼠标瞄准的方块**（最远 24 格，可配置）；悬空则自动落到最近的地面 |
| 开门次数 | **3 次**，每成功开一次门消耗 1 次；3 次用完后**物品消失**（创造模式不消耗） |
| 冷却 | 60 秒（可配置） |
| 传送门另一端（末影） | 距玩家 256 ~ 2048 格、**与当前群系不同**的生物群系 |
| 传送门另一端（地牢） | 附近**已加载区块**里的地牢，默认搜索半径 20 区块 |
| 可用次数 | **每人** 2 次往返（= 4 次单程穿行，可配置），各算各的 |
| 次数用尽（创造者） | 两端**整座门连高台和台阶一起消失**，不留遗迹 |
| 次数用尽（其他人） | 这个人不能再传送，但**门还开着**；不影响门的寿命 |
| 传送间隔 | 传送完之后要隔 **5 秒**才能再次被传送（可配置），避免在门口反复弹 |
| 被破坏 | 失效前任意组成方块被破坏 → **立即整对失效**，同样不留遗迹 |
| 新开一座门 | 该玩家**上一对还没失效的门立即消散**（跨维度也生效） |

使用流程：

1. 主手拿末影传送石（或地牢传送石），**用准星瞄准要开门的位置**，长按右键 4 秒，
   屏幕下方出现紫色进度条。
2. 引导完成后，在你瞄准的那块方块上生成一座「上行台阶 → 高台 → 传送门」的结构，
   同时在另一端生成与它相连的另一座门：
   - **末影传送石**：一个随机选中的**不同群系**，结构相同（也是台阶 + 高台）；
   - **地牢传送石**：附近的一个**地牢**，门框直接嵌在地牢房间的墙里（没有高台和台阶）。
3. 沿台阶走上高台、走进门 → 从对面那座门里走出来。
4. 传送完要等 5 秒才能再次被传送（动作栏会显示"传送冷却中…… 3 秒"）。
5. **次数按人算**：开门的人往返 2 次后两扇门**彻底消失**并提示"传送门已经消散了"；
   其他玩家也能用，各自有 2 次往返，用完后只是**自己**不能再传送，门还开着。

物品说明里除了功能简介，最后还有一行斜体来源文案：

- 末影传送石：**"这块石头似乎蕴含着末影的全部力量......"**
- 地牢传送石：**"火焰在石头表面游走，执拗地指向地底深处......"**

> 设计说明：需求里写的是"生成一个传送门，可以进入和返回 2 次"。
> 因为"返回"必须在另一端也有落脚的门，所以模组生成的是**一对互相连接的门**
> （一座在脚下、一座在远方群系）。如果你只想要"单程送过去、原地留一个返回锚点"，
> 改 `PortalManager.openPortalPair` 即可，见第 6 节。

### 传送门结构（v1.02 起改为「阶梯到门」）

结构朝玩家生成，台阶在外侧、门在高台上。下图是局部坐标（`z=0` 是门面，`z` 越大越靠近玩家）：

```
   局部 z=0（门面）         z=1          z=2    z=3    z=4
   y=4   ■ ■ ■ ■                                  ← 顶梁
   y=3   ■ ▓ ▓ ■
   y=2   ■ ▓ ▓ ■          ███████  ← 高台面（玩家站这里）
   y=1   ■ ▓ ▓ ■          ███████
   y=0   ■ ■ ■ ■  ■ ■ ■ ■ ███████  ← 底梁 + 高台
   y=-1                    ·  ·  ▲  ·  ·
   y=-2                    ·  ·  █  ▲  ·
   y=-3                    ·  ·  █  █  ▲   ← 最外侧台阶贴着地面

   ■ 黑曜石   ▓ 传送门方块（6 块，无碰撞、发光 11、可破坏）   █ 支撑填充   ▲ 磨制黑石台阶
```

- 高台比地面高 **4 格**，用 **3 级台阶**逐级走上去（每级 1 格），不会出现跳不上去的情况。
- 生成时会先挖空门洞、高台上方和台阶上方，再把高台从地面垫起来，所以不会"门埋在土里"。
- 四方向通用：所有方块坐标都由「原点 + 朝向」算出来（`PortalManager.localPos`），
  门面永远朝着玩家，台阶朝玩家这一侧铺。

### 门开在哪里（v1.03 起，v1.06 放宽）

引导完成时，服务端会从**玩家眼睛位置**沿**视线方向**做一次方块射线检测（最远 `aimDistance` 格）：

| 情况 | 结果 |
| --- | --- |
| 瞄准地面 / 石头 / 任何实心方块 | 门就开在那块方块上，台阶朝向玩家 |
| 瞄准树干、树叶、半空中的方块 | 先试瞄准点本身，站不住就**往下/往上找最近的地面**（最多试 6 层） |
| 瞄准悬崖侧壁、水面 | 往下找地面；那一列实在放不下就**按距离由近到远挪到邻近的平地**（最多 4 格） |
| 瞄准点那一带实在放不下 | **自动退回"玩家正前方"再试一次**（v1.06 新增，不再直接报错） |
| 对准天空 / 超出 24 格 | 直接走"玩家正前方 6 格" |

"站得住"的判定是：**那一格本身是空的（可替换）+ 下面一格顶面结实 + 不在水里**。
所以树叶、水面、悬空方块都不会被当成地面，门不会挂在半空。

v1.06 放宽了三处，避免"一直提示站不住脚"：

| 项目 | v1.05 及以前 | v1.06 |
| --- | --- | --- |
| 占地范围要求 | 含四周留白的 6×9 列**全部**要有天然地面 | 只要求真正放方块的 4×5 列，且允许最多 4 列缺失（用锚点高度补齐） |
| 地面落差上限 | 3 格 | 6 格 |
| "太近"判定 | 整个占地范围的 X/Z 都要避开玩家（站着不动都可能触发） | 只看玩家脚 / 头那两格会不会被实心方块压住 |

几处会直接给出提示、**不消耗次数也不进冷却**的情况：

- `这里的地面站不住脚` —— 瞄准点和玩家正前方都放不下整座门（基本只在悬崖正上方 / 大水面出现）；
- `离得太近了` —— 门会正好压在玩家身上，往远处瞄或退后几步即可；
- `没有找到合适的群系` —— 远端找不到别的群系（换位置再试）；
- `附近没有找到地牢` —— 视野内的已加载区块里没有地牢。

### 地牢传送石是怎么找到地牢的

**关键点：原版地牢（monster room）不是"结构"，而是"地物"。**
`ServerLevel#findNearestMapStructure` 只认要塞、矿井、村庄这类注册在 `StructureTags` 里的结构，
地牢根本不在那套索引里 —— 它由 `MonsterRoomFeature` 在区块生成时随机放置
（`monster_room.json`，每个区块 10 次尝试）。所以只能**翻区块找痕迹**。

判定用的是 **「刷怪笼 + 圆石地板」**：

| 判据 | 阈值 | 为什么能区分 |
| --- | --- | --- |
| 区块里有 `SpawnerBlockEntity` | —— | 地牢中央必定有一个刷怪笼 |
| 刷怪笼脚下 5×5 地板里圆石 / 苔石 | ≥ 6 块 | 地牢地板就是圆石和苔石 |
| 水平四个方向 2~5 格内有一面"顶面结实"的墙 | 至少一面 | 保证门框能嵌进墙里，而不是开在空地上 |

要塞是**石砖 + 蠹虫刷怪笼**、废弃矿井是**木板 + 铁轨**、下界要塞是**下界砖** ——
脚下都不是圆石，所以这条规则不会误判成别的结构。

搜索实现（`DungeonFinder`）：

- 从玩家所在区块开始**一圈圈往外扫**（切比雪夫距离），扫到第一圈有结果就停，
  所以拿到的必然是"最近那一圈里最近的"；
- 一次返回最近几个候选（默认最多 6 个），因为最近那个地牢可能四面都没有合适的墙、
  或者玩家自己就站在里面 —— 这时自动换下一个，而不是直接失败；
- 只用 `ServerChunkCache#getChunkNow` 读**已经加载**的区块，
  **不会**为了找地牢去同步生成新地形，所以不会卡服；
- 区块没加载就跳过 —— 所以实际能搜到的范围取决于你的视野/模拟距离。

地牢那一端为什么是"紧凑版"：地牢房间只有 5~7 格宽、**4 格高**，
完整结构的高台 + 台阶根本放不下。所以地牢这端的门框**直接嵌进房间的墙**：

```
   顶梁  ■ ■ ■ ■   ← 天花板下面那一层
   门面  ■ ▓ ▓ ■   ← 房间的空气层（3 格）
   底梁  ■ ■ ■ ■   ← 地板那一层
         ↑ 门正前方清一格，玩家从门口走出来就踩在地牢地板上
```

清理门前方块时会**跳过任何带方块实体的方块**，所以刷怪笼和战利品箱子不会被拆。
传送落点同样跳过带方块实体的格子，不会把刷怪笼清掉。

找不到地牢时提示「附近没有找到地牢，换个地方再试。」，**不消耗次数、不进冷却**。

### v1.06：为什么地牢门会"生成后立刻消失"

`LevelChunk#setBlockState` 在覆盖一个方块时，会对**旧方块**调用 `onRemove`：

```java
// LevelChunk.setBlockState
boolean flag2 = blockstate.hasBlockEntity();
if (!this.level.isClientSide) {
    blockstate.onRemove(this.level, p_62865_, p_62866_, p_62867_);  // blockstate = 旧方块
}
```

而 `RemotePortalBlock.onRemove` 的语义是"门面方块被移除了 → 整对门失效"。
所以只要建门过程中**任何一个门方块被后来的放置覆盖**，整对门就会在
**建成的同一个 tick 里被自己拆掉** —— 表现就是"生成后直接消失"。

最容易触发的情形：**玩家自己就站在地牢里**。这时近端结构（高台 + 台阶，占 4×5×9）
和地牢端结构（门框，嵌在同一间屋子的墙上）必然重叠，后建的那一座会覆盖先建的。

v1.06 的修法：

1. **建门期间屏蔽失效判定**：`PortalManager` 增加 `building` 标志，
   `placeStructure` 全程置位，`RemotePortalBlock.onRemove` 和 `invalidate` 都会跳过；
2. **落点互相避开**：算好近端结构的包围盒，地牢端只挑和它不相交的墙；
   四面都撞上就换下一个地牢候选；
3. **建完自检**：两座门都完整才提示成功，否则清理干净并报"附近没有找到地牢"，
   不会留半座门；
4. **自检容错**：框架自检要连续 2 次都不完整才判定失效（1 秒 → 2 秒），
   避免建门 / 区块加载瞬间的抖动误拆。

### 失效规则

| 触发条件 | 检测方式 | 结果 |
| --- | --- | --- |
| **创造者**的往返次数用尽 | `PortalManager.consumeUse` | 两端所有组成方块全部清除 |
| 其他人的次数用尽 | `PortalManager.consumeUse` | 只有这个人不能再传送，**门继续开着** |
| 门面方块被挖掉/炸掉/推掉 | `RemotePortalBlock.onRemove` | 立即清除两端全部方块 |
| 黑曜石框架被挖掉 | 每秒 1 次的结构自检（ticker） | 2 秒内清除两端全部方块 |
| **玩家又开了一对新门** | `PortalRegistry`（存档 `enderportal_portals.dat`） | 上一对门**立即**消散 |

所有拆除路径都会**同时拆掉对面那座门**，所以不会留下"半座门"。
拆除只清自己放的方块（黑曜石 / 磨制黑石台阶 / 传送门方块），不会误伤玩家建筑。

### 次数为什么按人算（v1.07）

之前整座门只有一个共享计数器，谁用都扣同一个数字，于是：

- 路过的人随便进两次，把门主的门弄没了；
- 多人一起玩时，先到的人把次数耗光，后面的人直接没得用。

现在每个门方块实体里存的是：

```
creator      : 开门那个人的 UUID
quota        : 每个人的满额次数（roundTrips * 2）
creatorLeft  : 创造者还剩几次        ← 这个归零，整座门失效
othersLeft   : { 玩家UUID -> 剩余 }  ← 这里归零只影响那个人自己
```

`othersLeft` 里**没有条目 = 满额**，所以新玩家第一次用就是完整配额；
用完的会被显式写成 `0`，从此被挡在门外。

两端各 6 个门方块都存一份，每次扣次数时会把两端 12 份一起对齐
（`PortalManager.applyUsage`），所以从哪一头进出读到的数字都一样。

非玩家实体（掉落物、生物）共用一个"公共桶"（`SHARED_ACTOR = UUID(0,0)`），
这样掉落物照常能穿过，又不会因为物品循环让计数表无限膨胀。

> 兼容旧存档：v1.06 及更早的 `Remaining` 标签会在读档时自动迁移成
> "创造者个人计数器"，行为不变，只是其他玩家从此有了自己的配额。

### v1.09：为什么多人时传送会失败还把次数扣光

`RemotePortalBlock` 的 `entityInside` 是被 `Entity.move()` 调用的
（`Entity.java:716` → `checkInsideBlocks()` → `entityInside`），
而 `move()` 又处在 `ServerGamePacketListenerImpl.handleMovePlayer` 的调用栈里 ——
那是处理客户端移动包的地方。它在 `move()` **返回之后**还会做一次位置纠偏：

```java
this.player.move(MoverType.PLAYER, ...);   // ← 传送在这里发生，人已经到对面了
d6 = d0 - this.player.getX();              // d0 是客户端上报的旧坐标
d10 = d6*d6 + d7*d7 + d8*d8;               // 位移差巨大 → 判定 "moved wrongly"
...
this.teleport(d3, d4, d5, ...);            // d3/d4/d5 是传送前的坐标 → 把人拉回来
```

于是传送在同一个 tick 内被原地撤销，而 `consumeUse` 已经执行了：
**次数扣了，人没走**。多走几次次数就耗光了。

多人比单人更容易撞上这条路径，因为客户端移动包更密集。

v1.09 的修法：

1. `entityInside` 只做判定和登记，真正的传送排到 `ServerTickEvent.Post` 执行。
   那个事件在 `tickServer` 尾部触发，`tickChildren`（含玩家移动包处理）早已返回，
   再没有任何东西会把玩家拉回去。
2. 新加的 `PortalTeleports` 队列按实体 UUID 去重 —— 一座门有 6 个方块，
   同一次移动检测会让 6 个方块实体各调一次 `entityInside`，不去重就会排 6 次队。
3. 扣次数改到传送真正完成之后，并且额外检查实体坐标是否真的落在目标点；
   没落过去就**不扣次数**，同时回滚冷却时间戳，不让玩家白等 5 秒。

---

## 2. 构建

需要 **JDK 21**。

### 方式 A：IntelliJ IDEA（推荐）

1. `File → Open` 选择本目录（`enderportal`），以 Gradle 项目导入。
2. 等待依赖下载完成（首次会从 `maven.neoforged.net` 拉取 NeoForge）。
3. 右侧 Gradle 面板 → `Tasks → neoforge runs → runClient` 启动开发客户端。
4. `Tasks → build → build` 生成 `build/libs/enderportal-1.10.jar`，丢进 `mods/` 即可。

### 方式 B：命令行（Gradle）

```powershell
# 需要本机已装 Gradle 8.8+（本项目只带了 wrapper 配置，没带 wrapper 的 jar）
gradle wrapper --gradle-version 8.8
.\gradlew build
```

### 方式 C：不用 Gradle，直接 javac + 打包（本机在用）

Gradle 在某些环境里起不来（`native-platform.dll` 加载失败）时，用这两个脚本绕过：

```powershell
powershell -ExecutionPolicy Bypass -File tools\compile.ps1   # 调 JBR 的 javac 直接编译 -> build\classes
python tools\package.py                                      # 把 class + 资源打成 jar -> build\libs\enderportal-1.10.jar
```

> `compile.ps1` 必须带 UTF-8 BOM 保存，否则 Windows PowerShell 5.1 会按 GBK 解析中文注释而报语法错。

`tools\compile.ps1` 的 classpath 由 NeoForm 的 `packRecomp/output.jar` + NeoForge + client-extra
+ Gradle 依赖缓存拼成，编译参数是 `--release 21 -Xlint:all`（零警告）。
`tools\package.py` 会把 `neoforge.mods.toml` / `pack.mcmeta` 里的 `${...}` 用 `gradle.properties`
展开，并固定 zip 时间戳，所以同样的输入永远得到同样的 jar（可复现）。

产物：`build/libs/enderportal-1.10.jar`（装到 NeoForge 21.1.x 的 `mods` 目录）。

> **本工程已经预置了一个编译好的成品**：`build/libs/enderportal-1.10.jar`
> （36 个条目，class 全部是 Java 21 字节码，已用 `javac --release 21` 对真实
> NeoForge 21.1.72 类路径编译通过，zip 完整性和 class 哈希都已核对）。
> 重新构建会覆盖它。放到 `.minecraft/mods/` 里、启动 NeoForge 21.1.x 即可加载。

### 版本号说明

当前锁的是官方 1.21.1 MDK 的初始组合，**这两个版本号确定存在**：

```properties
# build.gradle
id 'net.neoforged.gradle.userdev' version '7.0.145'
# gradle.properties
neo_version=21.1.72
```

想用更新的版本，先跑探测脚本拿到仓库里真实存在的版本号：

```powershell
powershell -ExecutionPolicy Bypass -File tools\find-versions.ps1
```

---

## 3. 构建报错怎么办

### 报错：`Could not resolve net.neoforged.gradle:userdev:<版本>`

这条错误只有两种原因，**先用脚本区分**：

```powershell
powershell -ExecutionPolicy Bypass -File tools\find-versions.ps1
```

> 先确认一件事：`settings.gradle` 和 `build.gradle` 必须在**同一层目录**，
> 而且 Gradle/IDEA 打开的就是 `enderportal` 这个目录本身。
> 如果打开的是它的上级目录，`settings.gradle` 里的 `pluginManagement` 仓库配置不会生效，
> Gradle 只会在 `plugins.gradle.org` 上找 NeoGradle —— 找不到，报的就是这条错。
> 自查：`Test-Path .\settings.gradle` 必须返回 `True`。

| 脚本输出 | 说明 | 处理 |
| --- | --- | --- |
| 能列出 NeoGradle 版本，但没有你写的那个 | 版本号不存在 | 把 `build.gradle` 里的 `7.0.145` 换成脚本输出的最新版本 |
| `取不到: ... 操作超时 / 连接被关闭` | 连不上 `maven.neoforged.net` | 是网络问题，见下面"配代理" |
| `取不到: ... 404` 之类 | 仓库地址变了 | 换 ModDevGradle 方案（见下） |

### 配代理（国内网络常见）

在 `gradle.properties` 末尾加（端口换成你自己代理的端口）：

```properties
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
```

或者在 IDE 里 `Settings → HTTP Proxy` 设置，然后 **File → Invalidate Caches** 后重新同步。

### 换 ModDevGradle（NeoGradle 实在搞不定时）

ModDevGradle 是 NeoForge 官方现在主推的构建插件，插件本体发布在 `plugins.gradle.org`，
比 `maven.neoforged.net` 好连得多。用法：

1. 把 `build.gradle` 改名成 `build.gradle.neogradle.bak`
2. 把 `tools/build.gradle.moddev` 复制成根目录的 `build.gradle`
3. 重新同步 / `.\gradlew build`

两种构建脚本的产物完全一样（`build/libs/enderportal-1.10.jar`），代码不用改一行。

### 其他常见报错

| 报错 | 原因 | 处理 |
| --- | --- | --- |
| `Unsupported class file major version` / `invalid source release: 21` | Gradle 用的 JDK 不是 21 | 装 JDK 21，`Settings → Build Tools → Gradle → Gradle JVM` 选它 |
| `Minimum supported Gradle version is 8.x` | Gradle 太旧 | `gradle wrapper --gradle-version 8.8` 后用 `.\gradlew` |
| `Could not resolve net.neoforged:neoforge:21.1.72` | NeoForge 版本号不存在 | 同上面的脚本，看 "NeoForge 本体" 那一栏 |

---

## 4. 配置

首次启动后生成 `config/enderportal-common.toml`：

```toml
[general]
    # 使用传送石需要引导多少秒（长按右键，松开会中断）
    channelSeconds = 4
    # 使用传送石后的冷却时间（秒）
    cooldownSeconds = 60
    # 同一个人/实体传送完之后，要隔多少秒才能再次被传送门传送
    teleportDelaySeconds = 5
    # 每个玩家可以往返几次（按人单独计算）
    roundTrips = 2
    # 传送门另一端距离玩家最近多少格
    minDistance = 256
    # 传送门另一端距离玩家最远多少格
    maxDistance = 2048
    # 门开在视线瞄准的位置，这是瞄准的最大距离（格）
    aimDistance = 24
    # 地牢传送石在玩家周围多少个区块内找地牢（1 区块 = 16 格）
    dungeonRadiusChunks = 20
```

想改成"只能往返一次"就把 `roundTrips` 设成 1（**每个人**都只能往返一次）；
想缩短引导就把 `channelSeconds` 改小
（客户端进度条会自动跟着变，因为进度是按 `getUseDuration` 算的）。
`teleportDelaySeconds` 控制"传送完多久才能再被传送"，默认 5 秒，
设成 0 就退回 v1.06 的 1 秒防抖行为。
`aimDistance` 调大就能把门开得更远（最大 64 格，即原版区块渲染距离内）。
`dungeonRadiusChunks` 调大能扩大地牢搜索范围（默认 20 区块 = 320 格，最大 48 区块），
但只有**已加载**的区块会被扫到，所以实际范围还受视野/模拟距离限制。
默认 20 区块比默认视野（10~12 区块）宽得多，所以视野内的地牢一定会被扫到。

> 键名从 v1.05 的 `dungeonSearchChunks` 改成了 `dungeonRadiusChunks`。
> 改名的原因是：NeoForge **不会覆盖**配置文件里已有的旧值，所以光改默认值
> 对已经生成过配置文件的存档完全无效 —— 换一个键名才能让新默认值真正生效。
> 旧键会留在文件里，可以手动删掉。

---

## 5. 代码结构

```
src/main/java/com/example/enderportal/
├── EnderPortal.java              模组主类：注册物品 / 方块 / 方块实体 / 配置 / 创造模式物品栏
├── Config.java                   ModConfigSpec 配置（含 channelSeconds、dungeonRadiusChunks）
├── item/
│   ├── AbstractPortalStoneItem.java  两件传送石的公共逻辑：长按引导、冷却、扣次数、物品说明
│   ├── PortalStoneItem.java      末影传送石：另一端开在远处另一个群系
│   └── DungeonPortalStoneItem.java   地牢传送石：另一端开在附近的地牢里
├── block/RemotePortalBlock.java  传送门方块：entityInside 登记传送、onRemove 破坏即失效、
│                                 getTicker 每秒结构自检、客户端粒子
├── block/RemotePortalBlockEntity.java
│                                 保存两端原点 + 朝向 + 各人剩余次数；executeTeleport 把实体送到对面高台
├── client/                       仅客户端（@EventBusSubscriber(Dist.CLIENT)）
│   ├── ClientSetup.java          注册 HUD 图层
│   └── ChannelHud.java           引导进度条
└── portal/
    ├── PortalManager.java        视线瞄准、落地搜索、局部坐标系、群系搜索、结构搭建/拆除、次数结算
    ├── DungeonFinder.java        翻已加载区块找地牢（刷怪笼 + 圆石地板）
    ├── PortalRegistry.java       SavedData：记录每个玩家上一对门，用于"新门顶掉旧门"
    ├── PortalTeleports.java      tick 末尾执行传送的队列（v1.09，避开 handleMovePlayer 的位置纠偏）
    └── PortalCooldowns.java      穿门防抖（默认 5 秒，可配置）
```

关键实现点：

- **4 秒引导**：完全复用原版"使用中"机制 ——
  `use()` 里 `player.startUsingItem(hand)` + `InteractionResultHolder.consume(stack)`，
  `getUseDuration()` 返回 `channelSeconds * 20`。原版每 tick 递减 `useItemRemaining`，
  走完调用 `completeUsingItem()` → `finishUsingItem()`（**只在服务端触发**）。
  中途松手走 `releaseUsing()`（这里故意留空），所以中断不扣次数、不进冷却。
- **进度条不需要网络包**：客户端直接读已经同步好的
  `player.isUsingItem()` / `getUseItem()` / `getTicksUsingItem()` 算比例，
  用 `RegisterGuiLayersEvent#registerAbove(VanillaGuiLayers.EXPERIENCE_BAR, ...)` 挂到 HUD 上。
- **四方向结构**：所有方块坐标都从「局部原点 + 朝向」换算（`PortalManager.localPos`），
  局部 +Z 永远是门外（台阶侧），所以玩家朝哪边看，台阶就朝哪边铺。
  楼梯的 `StairBlock.FACING` 指向门（局部 -Z），保证逐级往上走。
- **传送要挪出 `move()` 的调用栈**（v1.09）：`entityInside` 是在 `Entity.move()` 内部触发的，
  而 `move()` 又处在 `handleMovePlayer` 里 —— 那个方法返回后会用客户端上报的旧坐标做位置纠偏，
  把刚传送走的人拉回来。所以 `entityInside` 只登记，`PortalTeleports` 在
  `ServerTickEvent.Post` 统一执行，扣次数也挪到传送确认成功之后。
- **开门位置来自鼠标**（v1.03）：`PortalManager.aimTarget` 从眼睛位置沿视线方向做
  `Level#clip`（`ClipContext.Block.OUTLINE`，最远 `aimDistance` 格）拿到瞄准的方块；
  打空（对着天空）才退回"玩家正前方 6 格"的老逻辑。
- **不悬空**：`groundCandidates` 在瞄准的那一列上下**由近到远**列出"能站人"的高度
  （那格可替换 + 下面一格 `isFaceSturdy` + 不是流体），先试最近的；最近那层放不下整座门
  （地形太陡、有列找不到地面）就继续往下试，最多 4 层。
  这一列全废（悬崖边、水面）就由 `resolveOrigin` 按切比雪夫距离一圈圈往外找邻近的平地，最多 3 格。
- **搭建/拆除共用一份方块清单**（`PortalManager.pieces`）：坐标只由原点+朝向决定，
  不依赖运行时地形，所以拆除能 100% 还原成空气，不会剩半截柱子。
- **找群系**：在 256~2048 格范围内随机取 64 个候选点，用
  `level.getChunk(x, z, ChunkStatus.BIOMES, true)` 只生成到"群系"阶段就读取群系，
  避免每个候选点都完整生成地形；确认与当前群系不同后，才真正 `getChunk` 生成地形。
- **找落点**：远端同样用 `footprintSurfaces` 逐列找"能站人的地面"（不用 heightmap 的树叶/水面），
  要求周围没有岩浆，且占地范围起伏不超过 3 格（否则高台会很丑）。
- **地牢传送石的另一端**（v1.04）：`DungeonFinder` 一圈圈扫已加载区块的
  `LevelChunk#getBlockEntities()`，挑出 `SpawnerBlockEntity` 且脚下 5×5 有 ≥6 块圆石/苔石的
  那个（= 原版地牢）；`PortalManager.dungeonPlacement` 再从刷怪笼水平四方向 2~5 格内找一面
  `isFaceSturdy` 的墙，把门框**嵌进墙里**（`placeStructure(..., platform = false)` 只放门框 + 门面）。
  清理门前空间时会跳过带方块实体的方块，所以刷怪笼和箱子不会被拆掉。
- **搭建/拆除共用一份方块清单**（`PortalManager.pieces`）：坐标只由原点+朝向决定，
  不依赖运行时地形，所以拆除能 100% 还原成空气，不会剩半截柱子。
  紧凑版清单是完整版的**子集**，所以拆除永远按完整版走也不会漏。
- **找群系**：在 256~2048 格范围内随机取 64 个候选点，用
  `level.getChunk(x, z, ChunkStatus.BIOMES, true)` 只生成到"群系"阶段就读取群系，
  避免每个候选点都完整生成地形；确认与当前群系不同后，才真正 `getChunk` 生成地形。
- **数据持久化**：每个传送门方块各存一份
  `{自己原点, 自己朝向, 对面原点, 对面朝向, 创造者, 配额, 各人剩余次数}`，区块卸载重载后依旧可用。
- **防重复触发**：落点在门内高台上、面朝台阶，另外 `PortalCooldowns` 再做 5 秒冷却
  （`teleportDelaySeconds`，按实体各自计时）。
- **3 次开门**：直接复用原版耐久机制 —— `Item.Properties().durability(3)`，
  成功开门后 `stack.hurtAndBreak(1, level, player, consumer)`，`damage >= maxDamage` 时原版自动销毁物品。
- **新门顶掉旧门**：`PortalRegistry`（主世界 `data/enderportal_portals.dat`）记录每个玩家
  上一对门的维度/坐标/朝向。开门前先按记录把旧门整座拆掉，所以跨维度也生效。
  注意是**先确认新门能开成，再拆旧门**，避免"拆了旧的又没开成新的"。
- **破坏即失效**：
  - 门面方块被移除走 `Block.onRemove`（玩家挖掘、爆炸、活塞、`/setblock` 全覆盖）；
  - 黑曜石框架是原版方块没法挂钩，改用方块实体 ticker 每秒做一次 `isStructureIntact` 自检；
  - 两条路径都调用 `PortalManager.invalidate`，一次清掉两端整座结构；
  - 拆除过程用 `tearingDown` 静态标志做重入保护，否则 `setBlock` → `onRemove` 会无限递归。

---

## 6. 想改的地方

| 需求 | 改哪里 |
| --- | --- |
| 改成只生成一座门 | `PortalManager.openPortalPair` / `openDungeonPortalPair`：去掉其中一次 `placeStructure`，改为把玩家直接 `teleportTo` 到目标门 |
| 改引导时间 | `config/enderportal-common.toml` 的 `channelSeconds`（进度条会自动跟着变） |
| 改冷却时间 | `cooldownSeconds`（物品）/ `teleportDelaySeconds`（传送后间隔） |
| 改往返次数 | `roundTrips`（1 = 每人只能往返一次） |
| 改瞄准距离 | `aimDistance`（默认 24 格，最大 64） |
| 改找地面的耐心 | `PortalManager.GROUND_SEARCH_DOWN`（往下找多少格）、`MAX_GROUND_CANDIDATES`（同列试几层）、`MAX_ANCHOR_SHIFT`（最多挪几格） |
| 改"平地"的标准 | `PortalManager.MAX_FOOTPRINT_SPREAD`（占地范围内最大落差，默认 3） |
| 改开门次数（3 次） | `AbstractPortalStoneItem.MAX_USES`（改完需要重新构建；耐久上限在物品注册时就固定了） |
| 改成一次性消耗品 | `AbstractPortalStoneItem.MAX_USES = 1` |
| 改高台高度 / 台阶数 | `PortalManager.PLATFORM_RISE`（台阶数会自动跟着变：`PLATFORM_RISE - 1`） |
| 换台阶方块 | `PortalManager.pieces` 里的 `Blocks.POLISHED_BLACKSTONE_STAIRS`，记得同步改 `isOwnBlock` |
| 改进度条位置/颜色 | `client/ChannelHud`（`BOTTOM_MARGIN`、`BAR_WIDTH`、两个颜色值） |
| 允许同时存在多座门 | 去掉 `PortalManager.openPortalPair` 里的 `closePrevious(...)` 调用 |
| 换合成配方 | `data/enderportal/recipe/portal_stone.json`、`recipe/dungeon_portal_stone.json` |
| 换贴图 | `assets/enderportal/textures/item/portal_stone.png`、`item/dungeon_portal_stone.png`、`textures/block/remote_portal.png`（16×16） |
| 改来源文案 | `assets/enderportal/lang/zh_cn.json` 的 `tooltip.enderportal.portal_stone.lore` / `tooltip.enderportal.dungeon_stone.lore` |
| 改地牢搜索半径 | `config/enderportal-common.toml` 的 `dungeonRadiusChunks`（默认 20 区块，最大 48） |
| 改地牢判定 | `DungeonFinder.MIN_FLOOR_TILES`（脚下要有几块圆石才算地牢） |
| 让地牢那一端也用完整结构 | `PortalManager.openDungeonPortalPair` 里第二次 `placeStructure` 的 `false` 改成 `true` |

---

## 7. 已知情况（重要）

- **v1.10 的代码已用 `javac --release 21` 对真实的 NeoForge 21.1.72 类路径编译通过**
  （14 个源文件 → 28 个 class，`-Xlint:all` 无警告），jar 也已按同一份 class 打包。
  但**没有进游戏实测**（沙箱里 Gradle 无法启动，也起不了客户端），
  所以"瞄准点吸附到地面"的实际手感、结构生成位置、地牢定位的命中率、进度条位置、
  多人同时穿门的实际表现、粒子/音效这类体感效果，请以你本地实际游玩为准。
- 地牢搜索只扫**已加载区块**，所以：
  - 在地下挖矿、周围区块都加载着的时候最容易找到；
  - 如果提示"附近没有找到地牢"，往别处走走（让新区块加载）再试，
    或者把 `dungeonRadiusChunks` 调大。
- 贴图是用脚本生成的 16×16 像素图（`tools/gen_textures.py`），可以直接替换。
  地牢传送石那张是"紫色宝石 + 一圈火焰"（火焰环 72 像素、深紫镶边 24 像素、宝石 51 像素）。
- 传送门方块**可破坏**（硬度 0.3，无掉落物）；破坏任意一块 = 整对门失效。
  框架是黑曜石、台阶是磨制黑石台阶，挖掉同样会让整对门失效（最多 1 秒延迟）。
- 传送石用原版耐久机制实现 3 次上限，所以物品栏里会显示一条耐久条，
  条满 = 还能开 3 次门。创造模式下不消耗次数。
- 高台会**改动地形**（挖空 + 从地面垫起黑曜石柱），失效时这些方块会被清成空气，
  所以地面会留下一个浅坑——这是"不留遗迹"的必然结果。
  地牢那一端是紧凑结构，**不会**挖出坑。

