# 末影传送石 (Ender Portal Stone) v1.03

一个 Minecraft **1.21.1 / NeoForge 21.1.x** 小模组：用 8 块黑曜石 + 1 颗末影珍珠合成一颗"石头"，
**长按右键引导 4 秒**（屏幕下方有进度条）后，在**你鼠标瞄准的位置**与**另一个生物群系**之间
打开一对相通的「台阶 + 高台 + 传送门」，往返 2 次后自动失效，每次使用有 1 分钟冷却。
一块传送石最多只能开门 **3 次**，用完即毁；每次新开门，上一座还没失效的门会自动消散。

> **v1.03 变更**：门不再固定生成在"玩家正前方"，而是生成在**视线瞄准的方块**上；
> 瞄准点悬空（树冠、半空方块、悬崖侧壁）时会自动吸附到最近的**能站人的地面**，
> 不会把门架在半空。

---

## 1. 玩法

| 项目 | 数值 |
| --- | --- |
| 合成 | 3×3 工作台：8 块黑曜石围一圈，中间 1 颗末影珍珠 |
| **引导时间** | **4 秒**（可配置），长按右键，中途松手即中断、不消耗次数 |
| 引导进度条 | 屏幕下方居中显示，带百分比；数据来自原版同步状态，无需额外网络包 |
| **开门位置** | **鼠标瞄准的方块**（最远 24 格，可配置）；悬空则自动落到最近的地面 |
| 开门次数 | **3 次**，每成功开一次门消耗 1 次；3 次用完后**物品消失**（创造模式不消耗） |
| 冷却 | 60 秒（可配置） |
| 传送门另一端 | 距玩家 256 ~ 2048 格、**与当前群系不同**的生物群系 |
| 可用次数 | 每对门 2 次往返（= 4 次单程穿行，可配置） |
| 次数用尽 | 两端**整座门连高台和台阶一起消失**，不留遗迹 |
| 被破坏 | 失效前任意组成方块被破坏 → **立即整对失效**，同样不留遗迹 |
| 新开一座门 | 该玩家**上一对还没失效的门立即消散**（跨维度也生效） |

使用流程：

1. 主手拿末影传送石，**用准星瞄准要开门的位置**，长按右键 4 秒，屏幕下方出现紫色进度条。
2. 引导完成后，在你瞄准的那块方块上生成一座「上行台阶 → 高台 → 传送门」的结构，
   同时在一个随机选中的**不同群系**里生成与它相连的另一座门（结构相同）。
3. 沿台阶走上高台、走进门 → 从对面那座门的高台上走出来，落地后面朝台阶（门外）。
4. 反复往返，次数用尽时两扇门**彻底消失**，并提示"传送门已经消散了"。

物品说明里除了功能简介，最后还有一行斜体来源文案：
**"这块石头似乎蕴含着末影的全部力量......"**

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

### 门开在哪里（v1.03）

引导完成时，服务端会从**玩家眼睛位置**沿**视线方向**做一次方块射线检测（最远 `aimDistance` 格）：

| 情况 | 结果 |
| --- | --- |
| 瞄准地面 / 石头 / 任何实心方块 | 门就开在那块方块上，台阶朝向玩家 |
| 瞄准树干、树叶、半空中的方块 | 先试瞄准点本身，站不住就**往下/往上找最近的地面**（最多试 4 层） |
| 瞄准悬崖侧壁、水面 | 往下找地面；那一列实在放不下就**按距离由近到远挪到邻近的平地**（最多 3 格） |
| 对准天空 / 超出 24 格 | 退回到老行为：生成在玩家正前方 6 格 |

"站得住"的判定是：**那一格本身是空的（可替换）+ 下面一格顶面结实 + 不在水里**。
所以树叶、水面、悬空方块都不会被当成地面，门不会挂在半空。

几处会直接给出提示、**不消耗次数也不进冷却**的情况：

- `这里的地面站不住脚` —— 瞄准点周围 3 格内都找不到一块能放下整座门的平地；
- `离得太近了` —— 玩家自己正站在结构要占的那几格上，往远处瞄或退后几步即可；
- `没有找到合适的群系` —— 远端找不到别的群系（换位置再试）。

### 失效规则（v1.02）

| 触发条件 | 检测方式 | 结果 |
| --- | --- | --- |
| 往返次数用尽 | `PortalManager.consumeUse` | 两端所有组成方块全部清除 |
| 门面方块被挖掉/炸掉/推掉 | `RemotePortalBlock.onRemove` | 立即清除两端全部方块 |
| 黑曜石框架被挖掉 | 每秒 1 次的结构自检（ticker） | 1 秒内清除两端全部方块 |
| **玩家又开了一对新门** | `PortalRegistry`（存档 `enderportal_portals.dat`） | 上一对门**立即**消散 |

所有路径都会**同时拆掉对面那座门**，所以不会留下"半座门"。
拆除只清自己放的方块（黑曜石 / 磨制黑石台阶 / 传送门方块），不会误伤玩家建筑。

---

## 2. 构建

需要 **JDK 21**。

### 方式 A：IntelliJ IDEA（推荐）

1. `File → Open` 选择本目录（`enderportal`），以 Gradle 项目导入。
2. 等待依赖下载完成（首次会从 `maven.neoforged.net` 拉取 NeoForge）。
3. 右侧 Gradle 面板 → `Tasks → neoforge runs → runClient` 启动开发客户端。
4. `Tasks → build → build` 生成 `build/libs/enderportal-1.03.jar`，丢进 `mods/` 即可。

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
python tools\package.py                                      # 把 class + 资源打成 jar -> build\libs\enderportal-1.03.jar
```

> `compile.ps1` 必须带 UTF-8 BOM 保存，否则 Windows PowerShell 5.1 会按 GBK 解析中文注释而报语法错。

`tools\compile.ps1` 的 classpath 由 NeoForm 的 `packRecomp/output.jar` + NeoForge + client-extra
+ Gradle 依赖缓存拼成，编译参数是 `--release 21 -Xlint:all`（零警告）。
`tools\package.py` 会把 `neoforge.mods.toml` / `pack.mcmeta` 里的 `${...}` 用 `gradle.properties`
展开，并固定 zip 时间戳，所以同样的输入永远得到同样的 jar（可复现）。

产物：`build/libs/enderportal-1.03.jar`（装到 NeoForge 21.1.x 的 `mods` 目录）。

> **本工程已经预置了一个编译好的成品**：`build/libs/enderportal-1.03.jar`
> （26 个条目，class 全部是 Java 21 字节码，已用 `javac --release 21` 对真实
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

两种构建脚本的产物完全一样（`build/libs/enderportal-1.03.jar`），代码不用改一行。

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
    # 一对传送门可以往返几次，次数用尽后传送门失效
    roundTrips = 2
    # 传送门另一端距离玩家最近多少格
    minDistance = 256
    # 传送门另一端距离玩家最远多少格
    maxDistance = 2048
    # 门开在视线瞄准的位置，这是瞄准的最大距离（格）
    aimDistance = 24
```

想改成"只能往返一次"就把 `roundTrips` 设成 1；想缩短引导就把 `channelSeconds` 改小
（客户端进度条会自动跟着变，因为进度是按 `getUseDuration` 算的）。
`aimDistance` 调大就能把门开得更远（最大 64 格，即原版区块渲染距离内）。

---

## 5. 代码结构

```
src/main/java/com/example/enderportal/
├── EnderPortal.java              模组主类：注册物品 / 方块 / 方块实体 / 配置 / 创造模式物品栏
├── Config.java                   ModConfigSpec 配置（含 channelSeconds）
├── item/PortalStoneItem.java     长按引导：use 开始引导、finishUsingItem 才真正开门
│                                 物品说明：功能简介 + 剩余次数 + 往返次数 + 引导时间 + 斜体来源文案
├── block/RemotePortalBlock.java  传送门方块：entityInside 传送、onRemove 破坏即失效、
│                                 getTicker 每秒结构自检、客户端粒子
├── block/RemotePortalBlockEntity.java
│                                 保存两端原点 + 朝向 + 剩余次数；穿门时传送到对面高台
├── client/                       仅客户端（@EventBusSubscriber(Dist.CLIENT)）
│   ├── ClientSetup.java          注册 HUD 图层
│   └── ChannelHud.java           引导进度条
└── portal/
    ├── PortalManager.java        视线瞄准、落地搜索、局部坐标系、群系搜索、结构搭建/拆除、次数结算
    ├── PortalRegistry.java       SavedData：记录每个玩家上一对门，用于"新门顶掉旧门"
    └── PortalCooldowns.java      1 秒穿门防抖
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
- **数据持久化**：每个传送门方块各存一份
  `{自己原点, 自己朝向, 对面原点, 对面朝向, 剩余次数}`，区块卸载重载后依旧可用。
- **防重复触发**：落点在门内高台上、面朝台阶，另外 `PortalCooldowns` 再做 1 秒防抖。
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
| 改成只生成一座门 | `PortalManager.openPortalPair`：去掉其中一次 `placeStructure`，改为把玩家直接 `teleportTo` 到目标门 |
| 改引导时间 | `config/enderportal-common.toml` 的 `channelSeconds`（进度条会自动跟着变） |
| 改冷却时间 | `config/enderportal-common.toml` 的 `cooldownSeconds` |
| 改往返次数 | `roundTrips`（1 = 只能往返一次） |
| 改瞄准距离 | `aimDistance`（默认 24 格，最大 64） |
| 改找地面的耐心 | `PortalManager.GROUND_SEARCH_DOWN`（往下找多少格）、`MAX_GROUND_CANDIDATES`（同列试几层）、`MAX_ANCHOR_SHIFT`（最多挪几格） |
| 改"平地"的标准 | `PortalManager.MAX_FOOTPRINT_SPREAD`（占地范围内最大落差，默认 3） |
| 改开门次数（3 次） | `PortalStoneItem.MAX_USES`（改完需要重新构建；耐久上限在物品注册时就固定了） |
| 改成一次性消耗品 | `PortalStoneItem.MAX_USES = 1` |
| 改高台高度 / 台阶数 | `PortalManager.PLATFORM_RISE`（台阶数会自动跟着变：`PLATFORM_RISE - 1`） |
| 换台阶方块 | `PortalManager.pieces` 里的 `Blocks.POLISHED_BLACKSTONE_STAIRS`，记得同步改 `isOwnBlock` |
| 改进度条位置/颜色 | `client/ChannelHud`（`BOTTOM_MARGIN`、`BAR_WIDTH`、两个颜色值） |
| 允许同时存在多座门 | 去掉 `PortalManager.openPortalPair` 里的 `closePrevious(...)` 调用 |
| 换合成配方 | `data/enderportal/recipe/portal_stone.json` |
| 换贴图 | `assets/enderportal/textures/item/portal_stone.png`、`textures/block/remote_portal.png`（16×16） |
| 改来源文案 | `assets/enderportal/lang/zh_cn.json` 的 `tooltip.enderportal.portal_stone.lore` |

---

## 7. 已知情况（重要）

- **v1.03 的代码已用 `javac --release 21` 对真实的 NeoForge 21.1.72 类路径编译通过**
  （10 个源文件 → 14 个 class，`-Xlint:all` 无警告），jar 也已按同一份 class 打包。
  但**没有进游戏实测**（沙箱里 Gradle 无法启动，也起不了客户端），
  所以"瞄准点吸附到地面"的实际手感、结构生成位置、进度条位置、粒子/音效这类体感效果
  请以你本地实际游玩为准。
- 贴图是用脚本生成的 16×16 像素图（`tools/gen_textures.py`），可以直接替换。
- 传送门方块**可破坏**（硬度 0.3，无掉落物）；破坏任意一块 = 整对门失效。
  框架是黑曜石、台阶是磨制黑石台阶，挖掉同样会让整对门失效（最多 1 秒延迟）。
- 传送石用原版耐久机制实现 3 次上限，所以物品栏里会显示一条耐久条，
  条满 = 还能开 3 次门。创造模式下不消耗次数。
- 高台会**改动地形**（挖空 + 从地面垫起黑曜石柱），失效时这些方块会被清成空气，
  所以地面会留下一个浅坑——这是"不留遗迹"的必然结果。

