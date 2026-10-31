package com.example.enderportal.portal;

import com.example.enderportal.Config;
import com.example.enderportal.EnderPortal;
import com.example.enderportal.block.RemotePortalBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 传送门的生成 / 传送 / 失效逻辑。
 *
 * <p>结构是「高台 + 上行台阶 + 门」。所有坐标都用一个局部坐标系描述：
 * 局部原点 {@code origin} 是门底梁的左下角方块，局部 +Z 指向门外（玩家进出、上下台阶的一侧），
 * 局部 +X 与 +Z 垂直。因此同一套代码可以朝四个方向生成。</p>
 *
 * <pre>
 *   局部 z=0（门面）              z=1        z=2   z=3   z=4
 *   y=4   # # # #                                   ← 顶梁
 *   y=3   # P P #                                     P = 门面
 *   y=2   # P P #               高台面（玩家站这里）
 *   y=1   # P P #               高台
 *   y=0   # # # #  # # # #      底梁 + 高台面
 *   y=-1                        ·  ·  ▲  ·  ·          ▲ = 台阶，越靠外越低
 *   y=-2                        ·  ·  █  ▲  ·
 *   y=-3                        ·  ·  █  █  ▲
 *
 *   # 黑曜石   P 传送门方块   █ 支撑填充   ▲ 磨制黑石台阶
 * </pre>
 *
 * <p>失效规则：次数用尽、或任意一个组成方块被破坏，两端整座门（含高台和台阶）
 * 都会被完全清除，不留遗迹。</p>
 *
 * <p>近端的门开在玩家<b>视线瞄准的位置</b>：先做一次方块射线检测取瞄准的方块，
 * 再在那一列上下找最近的地面；地形放不下（悬崖边、水面、太陡）就挪到最近的平地，
 * 保证门不会悬空。</p>
 */
public final class PortalManager {

    /** 框架宽（含边框），沿局部 X 轴。 */
    public static final int FRAME_WIDTH = 4;
    /** 框架高（含边框），沿 Y 轴。 */
    public static final int FRAME_HEIGHT = 5;
    /** 门内部宽。 */
    public static final int PORTAL_WIDTH = 2;
    /** 门内部高。 */
    public static final int PORTAL_HEIGHT = 3;

    /** 高台面（玩家站立面）比地面高出的格数。 */
    public static final int PLATFORM_RISE = 4;
    /** 台阶数量，正好把高台拆成一级一格。 */
    public static final int STEP_COUNT = PLATFORM_RISE - 1;
    /** 高台进深（局部 z = 0..1）。 */
    public static final int PLATFORM_DEPTH = 2;

    /** 门底梁相对地面的高度：比高台面低一格。 */
    private static final int BASE_OFFSET = PLATFORM_RISE - 1;
    /** 支撑填充的底部相对底梁的偏移，拆除时用同一份常量，保证能拆干净。 */
    private static final int FILL_BOTTOM_OFFSET = -BASE_OFFSET;

    /** 生成时玩家与门之间的距离，玩家正好站在最外侧台阶前（只在没瞄准方块时使用）。 */
    private static final int APPROACH_DISTANCE = STEP_COUNT + 3;

    /** 瞄准点附近最多挪几格去找一块能站人的平地（切比雪夫距离）。 */
    private static final int MAX_ANCHOR_SHIFT = 4;
    /** 在瞄准的那一列上，向下最多找多少格地面。 */
    private static final int GROUND_SEARCH_DOWN = 48;
    /** 在瞄准的那一列上，向上最多找多少格地面（瞄准洞穴顶部之类的情况）。 */
    private static final int GROUND_SEARCH_UP = 12;
    /** 结构占地范围内，每列只在参考高度上下这么多格内找地面，避免把门架在悬崖底下。 */
    private static final int FOOTPRINT_WINDOW = 6;
    /**
     * 结构占地范围内地面最大落差，超过就说明地形太陡，换个位置。
     *
     * <p>这个值不能太小：真实地形（山坡、树根、台阶边）本来就起伏，
     * 卡太死会让"站不住脚"刷屏。高台本来就会从最低处垫黑曜石柱，
     * 所以只要落差在可控范围内就让它建。</p>
     */
    private static final int MAX_FOOTPRINT_SPREAD = 6;
    /** 同一列上最多试几层地面（瞄准树冠时往下多试几层就能落回林地）。 */
    private static final int MAX_GROUND_CANDIDATES = 6;
    /** 占地范围内允许有几列找不到天然地面（树、水、坑），用锚点高度补齐。 */
    private static final int MAX_MISSING_COLUMNS = 4;
    /** 地牢那一端：门框嵌进离刷怪笼多远的墙（地牢房间半径 2~3 格）。 */
    private static final int DUNGEON_WALL_MIN = 2;
    private static final int DUNGEON_WALL_MAX = 5;
    /** 一次最多试几个地牢候选（最近那个可能四面没墙，或者玩家自己站在里面）。 */
    private static final int MAX_DUNGEON_CANDIDATES = 6;
    /**
     * 找远端群系时，最多真正生成几个区块。
     *
     * <p>每个候选点都要 {@code getChunk(x, z)} 完整生成地形，在 2048 格外是很重的操作。
     * 上限给 3，最坏情况一次开门也只是几秒的零头；找不到就报"没有找到合适的群系"，
     * 让玩家换个位置再试，而不是硬卡。</p>
     */
    private static final int MAX_TARGET_CHUNKS = 3;

    /**
     * 拆门重入保护：拆除过程中 setBlock 会再次触发 onRemove，
     * 没有它就会无限递归。方块更新只在服务端主线程发生，普通静态量足够。
     */
    private static boolean tearingDown = false;

    /**
     * 建门保护。
     *
     * <p>{@code LevelChunk#setBlockState} 会对被覆盖的<b>旧方块</b>调用
     * {@code onRemove}，而我们的门方块在 {@code onRemove} 里会"整对门失效"。
     * 于是只要建门过程中有任何一个格子被后来的放置覆盖（典型情况：
     * 玩家自己就站在地牢里，近端结构和地牢端结构重叠），
     * 整对门就会在<b>建成的同一 tick 里被自己拆掉</b>，表现就是"生成后立刻消失"。
     * 建门期间把这个标志置上，{@code onRemove} 就不会再触发失效。</p>
     */
    private static boolean building = false;

    private PortalManager() {
    }

    /* ------------------------------------------------------------------ */
    /* 局部坐标 → 世界坐标                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * 把局部方块坐标换算成世界坐标。
     *
     * @param front 门外方向（局部 +Z）
     */
    public static BlockPos localPos(BlockPos origin, Direction front, int lx, int ly, int lz) {
        Direction lateral = front.getCounterClockWise();
        return origin.offset(
                lateral.getStepX() * lx + front.getStepX() * lz,
                ly,
                lateral.getStepZ() * lx + front.getStepZ() * lz);
    }

    /** 局部连续坐标 → 世界 X（用于算落点）。 */
    public static double localX(BlockPos origin, Direction front, double lx, double lz) {
        Direction lateral = front.getCounterClockWise();
        return origin.getX() + lateral.getStepX() * lx + front.getStepX() * lz;
    }

    /** 局部连续坐标 → 世界 Z。 */
    public static double localZ(BlockPos origin, Direction front, double lx, double lz) {
        Direction lateral = front.getCounterClockWise();
        return origin.getZ() + lateral.getStepZ() * lx + front.getStepZ() * lz;
    }

    /* ------------------------------------------------------------------ */
    /* 对外入口                                                            */
    /* ------------------------------------------------------------------ */

    /** 开门结果，用来给玩家不同的提示。 */
    public enum Result {
        /** 成功。 */
        OK,
        /** 瞄准点附近找不到能站人的地面。 */
        NO_GROUND,
        /** 玩家自己站在结构将要占用的位置上。 */
        TOO_CLOSE,
        /** 找不到另一个群系的落点。 */
        NO_TARGET,
        /** 附近没有找到地牢。 */
        NO_DUNGEON
    }

    /** 近端门的落点；失败时 {@code error} 非空。 */
    private record NearEnd(BlockPos origin, Direction front, Result error) {
        boolean ok() {
            return error == null;
        }
    }

    /**
     * 在玩家<b>瞄准的位置</b>开一对门：一座在鼠标指定的方块上（台阶朝向玩家），
     * 另一座在别的生物群系。开门前会先把该玩家上一对还没失效的门清掉。
     *
     * <p>瞄准点本身可能悬空（树叶、半空中的方块、悬崖侧面），所以先在那一列上下
     * 找最近的地面；那一列实在放不下，就按距离由近到远挪到邻近的平地，
     * 避免出现"门挂在半空"。</p>
     */
    public static Result openPortalPair(ServerLevel level, ServerPlayer player) {
        NearEnd near = resolveNearEnd(level, player);
        if (!near.ok()) {
            return near.error();
        }

        BlockPos target = findTarget(level, player.blockPosition(), near.front());
        if (target == null) {
            return Result.NO_TARGET;
        }

        int uses = Math.max(2, Config.ROUND_TRIPS.get() * 2);
        UUID creator = player.getUUID();
        closePrevious(level, player);
        placeStructure(level, near.origin(), near.front(), target, near.front(), creator, uses, true);
        placeStructure(level, target, near.front(), near.origin(), near.front(), creator, uses, true);
        remember(level, player, near.origin(), near.front(), target, near.front());

        Component biomeName = biomeName(level, target);
        player.displayClientMessage(Component.translatable("message.enderportal.created",
                biomeName, uses / 2).withStyle(ChatFormatting.LIGHT_PURPLE), false);

        return Result.OK;
    }

    /**
     * 在玩家<b>瞄准的位置</b>开一座门，另一端开在<b>附近的地牢</b>里。
     *
     * <p>地牢那一端不用「高台 + 台阶」，而是把门框直接嵌进地牢的墙里：
     * 地牢房间只有 5~7 格宽、4 格高，塞不下整座高台结构，
     * 而且玩家要的就是"落在地牢房间里"。门框底部与地牢地面齐平，
     * 玩家从门口走出来就是地牢的地板。</p>
     *
     * <p><b>建门顺序很重要</b>：先建地牢那一端，再建近端。两端结构如果有重叠，
     * 后建的会把先建的方块覆盖掉，而 {@code onRemove} 会把"方块被覆盖"当成
     * "玩家拆门"从而整对失效 —— 这就是"生成后立刻消失"的成因。
     * 这里用两重保险：先用包围盒排除会重叠的落点，建门期间再用
     * {@link #isBuilding()} 屏蔽掉 {@code onRemove} 的失效逻辑。</p>
     */
    public static Result openDungeonPortalPair(ServerLevel level, ServerPlayer player) {
        NearEnd near = resolveNearEnd(level, player);
        if (!near.ok()) {
            return near.error();
        }

        int radius = Math.max(1, Config.DUNGEON_RADIUS_CHUNKS.get());
        List<BlockPos> spawners = DungeonFinder.findDungeons(
                level, player.blockPosition(), radius, MAX_DUNGEON_CANDIDATES);
        if (spawners.isEmpty()) {
            return Result.NO_DUNGEON;
        }

        // 近端结构占的地方：地牢那一端不能和它撞上，否则互相覆盖会把门拆掉
        Box nearBox = structureBox(near.origin(), near.front(), true);

        NearEnd dungeon = null;
        for (BlockPos spawner : spawners) {
            dungeon = dungeonPlacement(level, spawner, nearBox);
            if (dungeon.ok()) {
                break;
            }
        }
        if (dungeon == null || !dungeon.ok() || !hasVerticalRoom(level, dungeon.origin())) {
            return Result.NO_DUNGEON;
        }

        BlockPos dungeonOrigin = dungeon.origin();
        Direction front = dungeon.front();

        int uses = Math.max(2, Config.ROUND_TRIPS.get() * 2);
        UUID creator = player.getUUID();
        closePrevious(level, player);

        // 地牢那一端用紧凑结构：只有门框 + 门面，没有高台和台阶
        placeStructure(level, dungeonOrigin, front, near.origin(), near.front(), creator, uses, false);
        placeStructure(level, near.origin(), near.front(), dungeonOrigin, front, creator, uses, true);

        // 兜底：万一还是被覆盖了，别把半座门留给玩家。
        // 只拆确实建成的那一端 —— 没建成的那一端坐标上可能是玩家自己的方块
        boolean nearIntact = isStructureIntact(level, near.origin(), near.front());
        boolean dungeonIntact = isStructureIntact(level, dungeonOrigin, front);
        if (!nearIntact || !dungeonIntact) {
            destroyIntactEnds(level,
                    nearIntact ? new End(near.origin(), near.front()) : null,
                    dungeonIntact ? new End(dungeonOrigin, front) : null);
            return Result.NO_DUNGEON;
        }

        remember(level, player, near.origin(), near.front(), dungeonOrigin, front);

        player.displayClientMessage(Component.translatable("message.enderportal.dungeon_created",
                uses / 2).withStyle(ChatFormatting.LIGHT_PURPLE), false);

        return Result.OK;
    }

    /** 记录"这个玩家最近开的门"，用于新门顶掉旧门。 */
    private static void remember(ServerLevel level, ServerPlayer player, BlockPos origin, Direction front,
                                 BlockPos partner, Direction partnerFront) {
        PortalRegistry.get(level.getServer()).remember(player.getUUID(),
                new PortalRegistry.Entry(level.dimension(), origin, front, partner, partnerFront));
    }

    /**
     * 算近端门该开在哪：先看准星指着哪，再在那一列 / 附近找一块放得下整座门的平地。
     *
     * <p>瞄准点实在放不下（悬崖正上方、水面、树冠）时，<b>再退回"玩家正前方"试一次</b>，
     * 而不是直接报"站不住脚"。玩家想要的是门开出来，不是一句错误提示。</p>
     */
    private static NearEnd resolveNearEnd(ServerLevel level, ServerPlayer player) {
        Direction front = player.getDirection().getOpposite();
        Result failure = Result.NO_GROUND;

        BlockPos aim = aimTarget(level, player);
        if (aim != null) {
            BlockPos origin = resolveOrigin(level, aim, aim.getY() + 1, front);
            if (origin != null) {
                if (playerClear(player, origin, front)) {
                    return new NearEnd(origin, front, null);
                }
                // 瞄准点能放下，但会压到玩家自己
                failure = Result.TOO_CLOSE;
            }
        }

        // 视线没打到方块，或者瞄准点那一带放不下：退回"玩家正前方"再试一次
        BlockPos fallback = fallbackAnchor(player, front);
        BlockPos origin = resolveOrigin(level, fallback, player.blockPosition().getY() + 1, front);
        if (origin != null && playerClear(player, origin, front)) {
            return new NearEnd(origin, front, null);
        }
        return new NearEnd(null, front, failure);
    }

    /**
     * 地牢里那座门的原点：把门框<b>嵌进地牢的墙</b>，门面朝向刷怪笼。
     *
     * <p>地牢房间只有 5~7 格宽、4 格高，塞不下「高台 + 台阶」，
     * 所以这里用紧凑结构。高度正好卡住：底梁落在刷怪笼那一层（地板），
     * 3 格门面占据房间的空气层，顶梁落在天花板下面那一层。</p>
     *
     * @return 结构原点 + 朝向；四面都没有可用的墙（不是正常的封闭房间）返回带错误的 {@link NearEnd}
     */
    private static NearEnd dungeonPlacement(ServerLevel level, BlockPos spawner, Box avoid) {
        for (Direction intoWall : Direction.Plane.HORIZONTAL) {
            BlockPos wall = findWall(level, spawner, intoWall);
            if (wall == null) {
                continue;
            }
            // 门面朝回房间内部（也就是朝刷怪笼）
            Direction front = intoWall.getOpposite();
            Direction lateral = front.getCounterClockWise();
            // 框架宽 4，往侧面挪 2 格让门洞大致对着刷怪笼
            BlockPos origin = wall.relative(lateral, -2).atY(spawner.getY() - 1);
            // 门正前方得是空的，否则玩家从门口走不出来 —— 换个方向再试
            if (!roomInFront(level, origin, front)) {
                continue;
            }
            // 和近端结构重叠就会互相覆盖，换一面墙
            if (structureBox(origin, front, false).intersects(avoid)) {
                continue;
            }
            return new NearEnd(origin, front, null);
        }
        return new NearEnd(null, Direction.NORTH, Result.NO_DUNGEON);
    }

    /** 结构占用的空间范围（轴对齐包围盒）。 */
    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean intersects(Box other) {
            return minX <= other.maxX && other.minX <= maxX
                    && minY <= other.maxY && other.minY <= maxY
                    && minZ <= other.maxZ && other.minZ <= maxZ;
        }
    }

    /**
     * 结构真正会动到的方块范围。
     *
     * <p>完整版包含台阶和高台，紧凑版只有门框那一层；两边都往四周留一格，
     * 免得两座门贴脸生成、互相把对方的框架挤掉。</p>
     */
    private static Box structureBox(BlockPos origin, Direction front, boolean platform) {
        int maxLz = platform ? PLATFORM_DEPTH + STEP_COUNT : 1;
        int minLy = platform ? -PLATFORM_RISE : 0;
        int maxLy = FRAME_HEIGHT;
        BlockPos a = localPos(origin, front, -1, minLy, -1);
        BlockPos b = localPos(origin, front, FRAME_WIDTH, maxLy, maxLz);
        return new Box(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    /**
     * 门正前方（局部 z=1）那 4×3 格能不能走人。
     *
     * <p>判定和清理逻辑保持一致：只要不是"有碰撞 + 带方块实体"（箱子、刷怪笼这种
     * 不能拆的东西），都算能清出来。这样既不会把门开在实心墙里，
     * 也不会因为地牢角落里有个箱子就整座门开不成。</p>
     */
    private static boolean roomInFront(ServerLevel level, BlockPos origin, Direction front) {
        for (int lx = 0; lx < FRAME_WIDTH; lx++) {
            for (int ly = 1; ly <= PORTAL_HEIGHT; ly++) {
                BlockPos pos = localPos(origin, front, lx, ly, 1);
                BlockState state = level.getBlockState(pos);
                boolean solid = !state.getCollisionShape(level, pos).isEmpty();
                if (solid && level.getBlockEntity(pos) != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 从刷怪笼朝 {@code dir} 走，找最近的实心方块（那就是地牢的墙）。
     *
     * <p>判定用"朝向房间那一面结实"，所以圆石、苔石、石砖、深板岩都算墙，
     * 而空气、水、火把这类挡不住人的方块不算。</p>
     *
     * @return 墙的坐标；2~5 格内没有墙返回 {@code null}
     */
    private static BlockPos findWall(ServerLevel level, BlockPos spawner, Direction dir) {
        for (int step = DUNGEON_WALL_MIN; step <= DUNGEON_WALL_MAX; step++) {
            BlockPos pos = spawner.relative(dir, step);
            if (level.getBlockState(pos).isFaceSturdy(level, pos, dir.getOpposite())) {
                return pos;
            }
        }
        return null;
    }

    /** 紧凑结构只占高度，检查一下有没有超出世界上下界。 */
    private static boolean hasVerticalRoom(ServerLevel level, BlockPos origin) {
        return origin.getY() > level.getMinBuildHeight()
                && origin.getY() + FRAME_HEIGHT < level.getMaxBuildHeight();
    }

    /**
     * 让该玩家上一对门失效（如果还在的话）。
     *
     * <p><b>必须先确认结构还在，再拆。</b>注册表里的条目在门自然失效后不会立刻清掉，
     * 所以这里的坐标很可能指向一片空地 —— 而那片空地早就被玩家重新利用过了。
     * 以前的写法是"不管还在不在都拆"，等于拿一段过期坐标去清玩家的黑曜石和台阶，
     * 4×5 范围内所有黑曜石都会被当成"我放的"清成空气。</p>
     *
     * <p>现在结构不完整就只清掉记录、直接返回，一根方块都不碰。</p>
     */
    private static void closePrevious(ServerLevel level, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        PortalRegistry registry = PortalRegistry.get(server);
        PortalRegistry.Entry previous = registry.get(player.getUUID());
        if (previous == null) {
            return;
        }
        registry.forget(player.getUUID());

        ServerLevel other = server.getLevel(previous.dimension());
        if (other == null) {
            return; // 那个维度没加载，只能等它自己失效
        }
        // 门早就没了：只清记录，绝不能拿过期坐标去拆方块
        if (!isStructureIntact(other, previous.origin(), previous.front())
                && !isStructureIntact(other, previous.partner(), previous.partnerFront())) {
            return;
        }
        invalidate(other, previous.origin(), previous.front(), previous.partner(), previous.partnerFront());
        player.displayClientMessage(Component.translatable("message.enderportal.previous_closed")
                .withStyle(ChatFormatting.DARK_PURPLE), true);
    }

    /**
     * 清掉落点上会把人卡住的方块。
     *
     * <p>只清<b>我们自己放的</b>方块（黑曜石 / 台阶 / 门面）和本来就可替换的东西
     * （草、雪、花、火把这种）。落点是我们自己建的高台，正常情况这里是空气，
     * 需要清就说明有人往门里塞了方块 —— 但那种方块可能是玩家的，不能随手清成空气。</p>
     *
     * <p>带方块实体的东西一律不动：地牢里的刷怪笼、战利品箱不会被落点清掉。</p>
     *
     * <p>落点所在的区块可能因为没人待在那儿而被卸载（多人的时候另一端常常是这种状态），
     * 所以先把区块显式拉起来再动方块，避免 {@code getBlockState} 在传送路径上做隐式同步加载。</p>
     */
    public static void clearLandingSpace(ServerLevel level, BlockPos feet) {
        // 先把落点那一片区块准备好：未加载时 getBlockState 会隐式加载，
        // 那是在传送的关键路径上做重活，而且会让后面的 setBlockAndUpdate 更不稳
        level.getChunkAt(feet);
        for (int i = 0; i < 2; i++) {
            BlockPos pos = feet.above(i);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || level.getBlockEntity(pos) != null) {
                continue;
            }
            if (!isOwnBlock(state) && !state.canBeReplaced()) {
                // 别人的方块：不动它，让原版的碰撞把人挤出去
                continue;
            }
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * 扣掉 {@code actor} 的一次使用次数，并把计数同步到两端。
     *
     * <p>次数是按人分开算的：只有<b>创造者</b>的计数归零才会拆掉整座门；
     * 其他玩家归零只是自己不能再传送，门继续开着。</p>
     *
     * <p>两端各 6 个方块都存着一份完整计数（谁用了几次都在里面），所以这里必须
     * <b>先读、改、再写回两端</b>。多人同时穿门时，两个调用会先后落在服务端主线程上，
     * 每次都从最新的 {@code source} 读起，不会互相覆盖 —— 前提是写回时以
     * {@code source} 为准整份复制（{@link RemotePortalBlockEntity#copyUsageFrom}），
     * 而不是各改各的。</p>
     *
     * @param actor 使用者（玩家 UUID，或非玩家实体共用的
     *              {@link RemotePortalBlockEntity#SHARED_ACTOR}）
     * @return 使用者扣完之后还剩几次；0 表示这次用完了
     */
    public static int consumeUse(ServerLevel level, BlockPos origin, Direction front,
                                 BlockPos partner, Direction partnerFront, UUID actor) {
        // 固定以"本端"为权威，不要用另一端的实体当 source：
        // 两端的 Map 顺序 / 加载时机可能不同，拿错一端会把过期的计数写回去
        RemotePortalBlockEntity source = firstPortal(level, origin, front);
        if (source == null) {
            return 0;
        }

        int left = Math.max(0, source.leftFor(actor) - 1);
        source.setLeftFor(actor, left);

        // 两端各 6 个方块都存着一份计数，必须一起更新，
        // 否则从另一端进出时读到的还是旧数字
        applyUsage(level, origin, front, source);
        applyUsage(level, partner, partnerFront, source);

        if (left <= 0 && actor.equals(source.getCreator())) {
            invalidate(level, origin, front, partner, partnerFront);
            return 0;
        }
        return left;
    }

    /** 把某一端的 6 个门方块实体的计数全部对齐到 {@code source}。 */
    private static void applyUsage(ServerLevel level, BlockPos origin, Direction front,
                                   RemotePortalBlockEntity source) {
        ensureChunks(level, origin, front);
        for (BlockPos pos : portalBlocks(origin, front)) {
            if (level.getBlockEntity(pos) instanceof RemotePortalBlockEntity portal && portal != source) {
                portal.copyUsageFrom(source);
            }
        }
    }

    /**
     * 让一对传送门彻底失效：两端所有组成方块（门面 + 框架 + 高台 + 台阶）全部清除。
     */
    public static void invalidate(ServerLevel level, BlockPos origin, Direction front,
                                  BlockPos partner, Direction partnerFront) {
        if (tearingDown || building) {
            return;
        }
        tearingDown = true;
        try {
            destroyStructure(level, origin, front, null);
            destroyStructure(level, partner, partnerFront, null);
        } finally {
            tearingDown = false;
        }
    }

    /** 一端门的坐标。 */
    private record End(BlockPos origin, Direction front) {
    }

    /**
     * 只拆指定的那几端（{@code null} 表示这一端没建成、不要碰）。
     *
     * <p>建门中途失败时用：没建成的那一端，坐标上可能是玩家的方块，
     * 按完整结构清单去拆会连玩家的黑曜石一起清掉。</p>
     */
    private static void destroyIntactEnds(ServerLevel level, End... ends) {
        if (tearingDown || building) {
            return;
        }
        tearingDown = true;
        try {
            for (End end : ends) {
                if (end != null) {
                    destroyStructure(level, end.origin(), end.front(), null);
                }
            }
        } finally {
            tearingDown = false;
        }
    }

    public static boolean isTearingDown() {
        return tearingDown;
    }

    /** 正在建门（此时任何"方块被覆盖"都不算玩家拆门）。 */
    public static boolean isBuilding() {
        return building;
    }

    /* ------------------------------------------------------------------ */
    /* 瞄准 / 落地                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 玩家视线（鼠标）指向的方块。
     *
     * <p>服务端用玩家眼睛位置 + 视线方向做一次方块射线检测，距离上限是配置里的
     * {@code aimDistance}。对着天空或超出距离时返回 {@code null}。</p>
     */
    public static BlockPos aimTarget(ServerLevel level, ServerPlayer player) {
        double reach = Math.max(2, Config.AIM_DISTANCE.get());
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(reach));
        BlockHitResult hit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    /** 视线没打到方块时，退回到"玩家正前方"的落点（台阶正对玩家）。 */
    private static BlockPos fallbackAnchor(ServerPlayer player, Direction front) {
        Direction lateral = front.getCounterClockWise();
        return player.blockPosition()
                .relative(front.getOpposite(), APPROACH_DISTANCE)
                .offset(-lateral.getStepX(), 0, -lateral.getStepZ());
    }

    /**
     * 从瞄准的那一列出发，找一块放得下整座门的平地，并换算出结构原点。
     *
     * <p>先在这一列上下按"离瞄准点最近"的顺序逐个试地面高度 —— 瞄准树干、树叶、
     * 悬空方块时，最近的那一层往往站不住整座门，再往下试就能落回真正的地面。
     * 这一列全都放不下（悬崖边、水面、太陡），再按距离由近到远试邻近的列。</p>
     *
     * @return 结构原点；放不下返回 {@code null}
     */
    private static BlockPos resolveOrigin(ServerLevel level, BlockPos anchor, int referenceY, Direction front) {
        Direction lateral = front.getCounterClockWise();
        // 同一个锚点周围会被反复试探（每个半径、每个 ground 候选各来一遍），
        // 探测的却是同一批列和高度，所以整轮共用一个按需填充的缓存
        ColumnCache cache = new ColumnCache(level, referenceY);
        try {
            return resolveOrigin(level, anchor, referenceY, front, lateral, cache);
        } finally {
            cache.clear();
        }
    }

    private static BlockPos resolveOrigin(ServerLevel level, BlockPos anchor, int referenceY, Direction front,
                                          Direction lateral, ColumnCache cache) {
        for (int radius = 0; radius <= MAX_ANCHOR_SHIFT; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = anchor.getX() + dx;
                    int z = anchor.getZ() + dz;
                    // 让瞄准的这一列落在高台中央（局部 x=2, z=1）
                    BlockPos probe = new BlockPos(
                            x - lateral.getStepX() * 2 - front.getStepX(), 0,
                            z - lateral.getStepZ() * 2 - front.getStepZ());

                    // 一列上可能有好几层能站人的地面，逐个试着放下整座门
                    List<Integer> grounds = cache.groundCandidatesAt(x, z, referenceY,
                            GROUND_SEARCH_DOWN, GROUND_SEARCH_UP, MAX_GROUND_CANDIDATES);
                    for (int ground : grounds) {
                        int[][] surfaces = footprintSurfaces(level, probe, front, ground, cache);
                        if (surfaces == null) {
                            continue;
                        }
                        Bounds bounds = boundsOf(surfaces);
                        if (bounds.tooSteep()) {
                            continue; // 这一层地形太陡，往下试
                        }
                        // 从占地范围内最低的一列开始垫，整座高台都踩在地上
                        BlockPos origin = new BlockPos(probe.getX(), bounds.min() + BASE_OFFSET, probe.getZ());
                        if (hasRoom(level, origin, front)) {
                            return origin;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 一次开门内部的"这一列能不能站人"缓存。
     *
     * <p>{@code footprintSurfaces} 会被反复调用（每个候选地面高度一次），探测的却是同一批
     * 列和高度。没有缓存时最坏情况一次开门要十几万次 {@code getBlockState}。</p>
     *
     * <p>按需扫描：某列第一次被问到时才逐格查，查到哪记到哪
     * （{@code 0} 未知 / {@code 1} 能站 / {@code 2} 不能站）。
     * 这样最常见的"瞄准点一下就成立"仍然只探测十几格，不会为了缓存预先扫满整条搜索带。</p>
     *
     * <p>缓存只在一次 {@code resolveOrigin} 内有效，不跨轮次复用，所以不会读到过期地形。</p>
     */
    private static final class ColumnCache {

        private static final byte UNKNOWN = 0;
        private static final byte STANDABLE = 1;
        private static final byte BLOCKED = 2;

        private final ServerLevel level;
        private final int bandLow;
        private final int bandHigh;
        private final Map<Long, byte[]> columns = new HashMap<>();

        ColumnCache(ServerLevel level, int referenceY) {
            this.level = level;
            // 查询时的参考高度可能是某个 ground 候选（与 referenceY 相差最多 GROUND_SEARCH_DOWN），
            // 再上下各探 FOOTPRINT_WINDOW，所以可查范围要比主窗口再宽一圈
            int margin = FOOTPRINT_WINDOW;
            this.bandLow = Math.max(level.getMinBuildHeight(), referenceY - GROUND_SEARCH_DOWN - margin);
            this.bandHigh = Math.min(level.getMaxBuildHeight() - 1, referenceY + GROUND_SEARCH_UP + margin);
        }

        private static long key(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
        }

        private byte[] column(int x, int z) {
            long key = key(x, z);
            byte[] cached = columns.get(key);
            if (cached != null) {
                return cached;
            }
            byte[] states = new byte[Math.max(0, bandHigh - bandLow + 1)];
            columns.put(key, states);
            return states;
        }

        private boolean standable(int x, int z, int y) {
            if (y < bandLow || y > bandHigh) {
                return false;
            }
            byte[] states = column(x, z);
            int index = y - bandLow;
            if (states[index] == UNKNOWN) {
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, y, z);
                states[index] = isStandable(level, cursor) ? STANDABLE : BLOCKED;
            }
            return states[index] == STANDABLE;
        }

        /** 这一列离 {@code referenceY} 最近的可站立高度；窗口内没有返回 {@code null}。 */
        Integer groundAt(int x, int z, int referenceY, int maxDown, int maxUp) {
            for (int distance = 0; distance <= Math.max(maxDown, maxUp); distance++) {
                if (distance <= maxDown && standable(x, z, referenceY - distance)) {
                    return referenceY - distance;
                }
                if (distance > 0 && distance <= maxUp && standable(x, z, referenceY + distance)) {
                    return referenceY + distance;
                }
            }
            return null;
        }

        /**
         * 这一列按"离 {@code referenceY} 由近到远"的可站立高度，最多 {@code limit} 个。
         *
         * <p>必须给出多个：目标列的地面常常放不下整座门（树冠、崖顶），
         * 要能继续往下试到真正的地面，否则就又退回"瞄哪儿都站不住脚"。</p>
         */
        List<Integer> groundCandidatesAt(int x, int z, int referenceY, int maxDown, int maxUp, int limit) {
            List<Integer> out = new ArrayList<>(limit);
            for (int distance = 0; distance <= Math.max(maxDown, maxUp) && out.size() < limit; distance++) {
                if (distance <= maxDown && standable(x, z, referenceY - distance)) {
                    out.add(referenceY - distance);
                    if (out.size() >= limit) {
                        break;
                    }
                }
                if (distance > 0 && distance <= maxUp && standable(x, z, referenceY + distance)) {
                    out.add(referenceY + distance);
                }
            }
            return out;
        }

        void clear() {
            columns.clear();
        }
    }

    /**
     * 结构会不会把玩家自己封进去（瞄准脚下时）。
     *
     * <p>只挡真正会压到玩家的格子：玩家脚 / 头所在的那两格如果正好要放<b>实体方块</b>，
     * 才算"太近"。以前是拿整个占地范围（含台阶外沿和四周留白）去比 X/Z，
     * 玩家站在 5 格外都会被判太近，正常瞄准几乎必然触发。</p>
     */
    private static boolean playerClear(ServerPlayer player, BlockPos origin, Direction front) {
        BlockPos feet = player.blockPosition();
        BlockPos head = feet.above();
        for (Piece piece : pieces(origin, front,
                EnderPortal.REMOTE_PORTAL.get().defaultBlockState(), true)) {
            BlockPos pos = piece.pos();
            if (pos.equals(feet) || pos.equals(head)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 在 (x, z) 这一列上，从 {@code referenceY} 起上下交替地找最近的一个能站人的位置。
     *
     * @return 地面那一格的 y（脚踩的高度）；找不到返回 {@code null}
     */
    private static Integer groundNear(ServerLevel level, int x, int z, int referenceY, int maxDown, int maxUp) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        for (int distance = 0; distance <= Math.max(maxDown, maxUp); distance++) {
            if (distance <= maxDown) {
                int y = referenceY - distance;
                if (y >= minY) {
                    cursor.set(x, y, z);
                    if (isStandable(level, cursor)) {
                        return y;
                    }
                }
            }
            if (distance > 0 && distance <= maxUp) {
                int y = referenceY + distance;
                if (y <= maxY) {
                    cursor.set(x, y, z);
                    if (isStandable(level, cursor)) {
                        return y;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 这一格能不能站人：本身是空的（能被替换），下面一格顶面结实，且不在水里。
     *
     * <p>树叶单独排除掉 —— 树冠顶面"看起来"是实心的，但把门架在树上同样算悬空，
     * 应该继续往下找真正的地面。水面、半空中的方块也会因此被跳过。</p>
     */
    private static boolean isStandable(ServerLevel level, BlockPos.MutableBlockPos pos) {
        BlockState here = level.getBlockState(pos);
        if (!here.getFluidState().isEmpty() || !here.canBeReplaced()) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        if (!floor.getFluidState().isEmpty() || floor.is(BlockTags.LEAVES)) {
            return false;
        }
        return floor.isFaceSturdy(level, below, Direction.UP);
    }

    /* ------------------------------------------------------------------ */
    /* 地形 / 生物群系 / 落点搜索                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 结构占地范围内所有列的地面高度。
     *
     * <p>只检查<b>真正会放方块</b>的那些列（高台 + 台阶，4×5），四周留白不再要求
     * 必须有地面 —— 以前连留白一起要求，一棵树、一滩水就能让整次开门失败。</p>
     *
     * <p>个别列找不到天然地面（树干、水面、坑）时，用锚点列的高度补齐，
     * 但只要缺得太多（超过 {@link #MAX_MISSING_COLUMNS}）就认为地形不适合，返回
     * {@code null}。高台本身会从最低处垫黑曜石柱，所以补齐是安全的。</p>
     */
    private static int[][] footprintSurfaces(ServerLevel level, BlockPos origin, Direction front, int referenceY) {
        return footprintSurfaces(level, origin, front, referenceY, null);
    }

    private static int[][] footprintSurfaces(ServerLevel level, BlockPos origin, Direction front, int referenceY,
                                             ColumnCache cache) {
        int columns = PLATFORM_DEPTH + STEP_COUNT; // 高台 + 台阶
        int[][] surfaces = new int[FRAME_WIDTH][columns];
        int missing = 0;
        for (int ix = 0; ix < FRAME_WIDTH; ix++) {
            for (int iz = 0; iz < columns; iz++) {
                BlockPos pos = localPos(origin, front, ix, 0, iz);
                Integer ground = cache == null
                        ? groundNear(level, pos.getX(), pos.getZ(), referenceY,
                                FOOTPRINT_WINDOW, FOOTPRINT_WINDOW)
                        : cache.groundAt(pos.getX(), pos.getZ(), referenceY,
                                FOOTPRINT_WINDOW, FOOTPRINT_WINDOW);
                if (ground == null) {
                    if (++missing > MAX_MISSING_COLUMNS) {
                        return null;
                    }
                    surfaces[ix][iz] = referenceY;
                } else {
                    surfaces[ix][iz] = ground;
                }
            }
        }
        return surfaces;
    }

    /** 一趟扫出占地范围的最低 / 最高地面。 */
    private record Bounds(int min, int max) {
        int spread() {
            return max - min;
        }

        boolean tooSteep() {
            return spread() > MAX_FOOTPRINT_SPREAD;
        }
    }

    private static Bounds boundsOf(int[][] values) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int[] row : values) {
            for (int value : row) {
                if (value < min) {
                    min = value;
                }
                if (value > max) {
                    max = value;
                }
            }
        }
        return new Bounds(min, max);
    }

    /** 结构整体是否放得下（不超世界高度、没有岩浆）。 */
    private static boolean hasRoom(ServerLevel level, BlockPos origin, Direction front) {
        if (origin.getY() - PLATFORM_RISE < level.getMinBuildHeight() + 1) {
            return false;
        }
        if (origin.getY() + FRAME_HEIGHT + 2 > level.getMaxBuildHeight()) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int lx = -1; lx <= FRAME_WIDTH; lx++) {
            for (int lz = -1; lz <= STEP_COUNT + 2; lz++) {
                for (int ly = -PLATFORM_RISE; ly <= FRAME_HEIGHT; ly++) {
                    BlockPos pos = localPos(origin, front, lx, ly, lz);
                    cursor.set(pos.getX(), pos.getY(), pos.getZ());
                    if (level.getFluidState(cursor).is(FluidTags.LAVA)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 找另一座门的位置；找不到返回 null。
     *
     * <p>开销主要在"真正生成地形"：{@code getChunk(x, z, BIOMES, true)} 只到群系阶段，
     * 很便宜；但确认群系不同之后要 {@code getChunk(x, z)} 完整生成，那是重活。
     * 所以：</p>
     * <ul>
     *   <li>候选点数量上限降到 {@link #MAX_TARGET_CHUNKS}，满 2048 格开一次门最多生成
     *       这么多个区块，不会一卡好几秒；</li>
     *   <li>完整生成前先用已有的高度图粗筛一遍，明显放不下（水面、悬崖）的直接放弃，
     *       省掉一次地形生成。</li>
     * </ul>
     */
    private static BlockPos findTarget(ServerLevel level, BlockPos playerPos, Direction front) {
        ResourceKey<Biome> currentKey = level.getBiome(playerPos).unwrapKey().orElse(null);

        RandomSource random = level.getRandom();
        int minDistance = Math.max(32, Config.MIN_DISTANCE.get());
        int maxDistance = Math.max(minDistance + 64, Config.MAX_DISTANCE.get());
        Direction lateral = front.getCounterClockWise();
        int probeY = Mth.clamp(64, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);

        int generated = 0;
        for (int attempt = 0; attempt < 64 && generated < MAX_TARGET_CHUNKS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = minDistance + random.nextInt(maxDistance - minDistance);
            int x = playerPos.getX() + (int) Math.round(Math.cos(angle) * (double) distance);
            int z = playerPos.getZ() + (int) Math.round(Math.sin(angle) * (double) distance);

            // 只生成到 BIOMES 阶段就能读到群系，避免每个候选点都完整生成区块
            ChunkAccess biomeChunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.BIOMES, true);
            if (biomeChunk == null) {
                continue;
            }
            Holder<Biome> candidate = biomeChunk.getNoiseBiome(
                    QuartPos.fromBlock(x), QuartPos.fromBlock(probeY), QuartPos.fromBlock(z));
            ResourceKey<Biome> candidateKey = candidate.unwrapKey().orElse(null);
            if (candidateKey == null || (currentKey != null && candidateKey.equals(currentKey))) {
                continue; // 和当前群系相同，换一个
            }

            // 粗筛：这片区块已经有高度图了就先看一眼，明显是水面就别浪费一次地形生成。
            // 没到那一步就当作"还不知道"，继续往下走。
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z));
            BlockPos floor = surface.below();
            if (!biomeChunk.getBlockState(floor).getFluidState().isEmpty()) {
                continue;
            }

            // 命中不同群系 + 粗筛通过，才真正生成地形
            level.getChunk(x >> 4, z >> 4);
            generated++;

            // 让这一列落在高台中央（局部 x=2, z=1）
            BlockPos probe = new BlockPos(
                    surface.getX() - lateral.getStepX() * 2 - front.getStepX(),
                    0,
                    surface.getZ() - lateral.getStepZ() * 2 - front.getStepZ());

            // 每一列都真的找到"站得住的地面"才建；水面、树叶、悬崖边会被跳过
            int[][] surfaces = footprintSurfaces(level, probe, front, surface.getY());
            if (surfaces == null) {
                continue;
            }
            Bounds bounds = boundsOf(surfaces);
            if (bounds.tooSteep()) {
                continue; // 地形起伏太大，高台会很难看
            }
            BlockPos origin = new BlockPos(probe.getX(), bounds.min() + BASE_OFFSET, probe.getZ());
            if (!hasRoom(level, origin, front)) {
                continue;
            }
            return origin;
        }
        return null;
    }

    private static Component biomeName(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .<Component>map(key -> Component.translatable(key.location().toLanguageKey("biome")))
                .orElse(Component.translatable("message.enderportal.unknown_biome"));
    }

    /* ------------------------------------------------------------------ */
    /* 结构搭建 / 拆除                                                      */
    /* ------------------------------------------------------------------ */

    /** 一个待放置的方块。 */
    private record Piece(BlockPos pos, BlockState state) {
    }

    /**
     * 生成整座门：先挖空门洞与台阶上方，再摆框架、高台、台阶和门面。
     *
     * <p>全程持有 {@code building} 标志：建门期间被覆盖的方块不算"玩家拆门"，
     * 否则后建的那一端会把先建的那一端拆掉（见 {@link #building}）。</p>
     *
     * @param platform {@code true} 为完整的「台阶 + 高台 + 门」；
     *                 {@code false} 为紧凑版（只有门框 + 门面），给地牢这种室内空间用
     */
    private static void placeStructure(ServerLevel level, BlockPos origin, Direction front,
                                       BlockPos partnerOrigin, Direction partnerFront,
                                       UUID creator, int uses, boolean platform) {
        boolean previousBuilding = building;
        building = true;
        try {
            ensureChunks(level, origin, front);

            BlockState air = Blocks.AIR.defaultBlockState();
            if (platform) {
                // 1) 挖空：门洞 + 高台上方
                for (int lx = 0; lx < FRAME_WIDTH; lx++) {
                    for (int lz = 0; lz < PLATFORM_DEPTH; lz++) {
                        for (int ly = 1; ly <= FRAME_HEIGHT; ly++) {
                            level.setBlockAndUpdate(localPos(origin, front, lx, ly, lz), air);
                        }
                    }
                }
                // 台阶上方留出站立空间
                for (int step = 0; step < STEP_COUNT; step++) {
                    int lz = PLATFORM_DEPTH + step;
                    int stepY = -1 - step;
                    for (int lx = 1; lx <= PORTAL_WIDTH; lx++) {
                        for (int ly = stepY + 1; ly <= stepY + 4; ly++) {
                            level.setBlockAndUpdate(localPos(origin, front, lx, ly, lz), air);
                        }
                    }
                }
            } else {
                // 紧凑版：地牢房间本来就是空的，只把门正前方那一列清出来，
                // 而且绝不碰刷怪笼和箱子（那是地牢的战利品，不能拆）
                for (int lx = 1; lx <= PORTAL_WIDTH; lx++) {
                    for (int ly = 1; ly <= PORTAL_HEIGHT; ly++) {
                        BlockPos inFront = localPos(origin, front, lx, ly, 1);
                        BlockState state = level.getBlockState(inFront);
                        if (!state.isAir() && level.getBlockEntity(inFront) == null) {
                            level.setBlockAndUpdate(inFront, air);
                        }
                    }
                }
            }

            // 2) 摆放
            BlockState portalState = EnderPortal.REMOTE_PORTAL.get().defaultBlockState();
            for (Piece piece : pieces(origin, front, portalState, platform)) {
                level.setBlockAndUpdate(piece.pos(), piece.state());
                if (piece.state().is(EnderPortal.REMOTE_PORTAL.get())
                        && level.getBlockEntity(piece.pos()) instanceof RemotePortalBlockEntity portal) {
                    portal.configure(origin, front, partnerOrigin, partnerFront, creator, uses);
                }
            }

            level.playSound(null, localPos(origin, front, 2, 2, 1), SoundEvents.PORTAL_AMBIENT,
                    SoundSource.BLOCKS, 0.6F, 1.2F);
        } finally {
            building = previousBuilding;
        }
    }

    /**
     * 整座门要放的所有方块（含支撑填充）。
     * 坐标只由 origin / front 决定，所以搭建和拆除共用这一份清单，保证拆得干净。
     *
     * @param platform {@code false} 时只返回门框 + 门面（紧凑版是完整版的<b>子集</b>），
     *                 这样拆除时永远按完整版清单走也不会漏
     */
    private static List<Piece> pieces(BlockPos origin, Direction front, BlockState portalState,
                                      boolean platform) {
        List<Piece> out = new ArrayList<>(96);
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        // 楼梯的高侧朝向门（局部 -Z），玩家从外侧逐级走上来
        BlockState stair = Blocks.POLISHED_BLACKSTONE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, front.getOpposite());

        int fillBottom = origin.getY() + FILL_BOTTOM_OFFSET;

        // 框架：底梁、顶梁、左右立柱
        for (int lx = 0; lx < FRAME_WIDTH; lx++) {
            out.add(new Piece(localPos(origin, front, lx, 0, 0), obsidian));
            out.add(new Piece(localPos(origin, front, lx, FRAME_HEIGHT - 1, 0), obsidian));
        }
        for (int ly = 1; ly < FRAME_HEIGHT - 1; ly++) {
            out.add(new Piece(localPos(origin, front, 0, ly, 0), obsidian));
            out.add(new Piece(localPos(origin, front, FRAME_WIDTH - 1, ly, 0), obsidian));
        }

        // 门面
        for (int lx = 1; lx <= PORTAL_WIDTH; lx++) {
            for (int ly = 1; ly <= PORTAL_HEIGHT; ly++) {
                out.add(new Piece(localPos(origin, front, lx, ly, 0), portalState));
            }
        }

        if (!platform) {
            return out;
        }

        // 高台：表面一层 + 往下垫到地面
        for (int lx = 0; lx < FRAME_WIDTH; lx++) {
            for (int lz = 0; lz < PLATFORM_DEPTH; lz++) {
                BlockPos surfacePos = localPos(origin, front, lx, 0, lz);
                if (lz > 0) {
                    out.add(new Piece(surfacePos, obsidian));
                }
                for (int y = fillBottom; y < surfacePos.getY(); y++) {
                    out.add(new Piece(new BlockPos(surfacePos.getX(), y, surfacePos.getZ()), obsidian));
                }
            }
        }

        // 台阶：越靠外越低，每级一格
        for (int step = 0; step < STEP_COUNT; step++) {
            int lz = PLATFORM_DEPTH + step;
            int stepY = -1 - step;
            for (int lx = 1; lx <= PORTAL_WIDTH; lx++) {
                BlockPos stepPos = localPos(origin, front, lx, stepY, lz);
                out.add(new Piece(stepPos, stair));
                for (int y = fillBottom; y < stepPos.getY(); y++) {
                    out.add(new Piece(new BlockPos(stepPos.getX(), y, stepPos.getZ()), obsidian));
                }
            }
        }

        return out;
    }

    /** 一座门里所有传送门方块的位置。 */
    public static List<BlockPos> portalBlocks(BlockPos origin, Direction front) {
        List<BlockPos> positions = new ArrayList<>(PORTAL_WIDTH * PORTAL_HEIGHT);
        for (int lx = 1; lx <= PORTAL_WIDTH; lx++) {
            for (int ly = 1; ly <= PORTAL_HEIGHT; ly++) {
                positions.add(localPos(origin, front, lx, ly, 0));
            }
        }
        return positions;
    }

    /** 负责做结构自检的那个门方块（左下角），其余方块不重复检查。 */
    public static BlockPos masterPos(BlockPos origin, Direction front) {
        return localPos(origin, front, 1, 1, 0);
    }

    /**
     * 结构是否完整：6 个门面方块和 14 个黑曜石框架部件都还在。
     * 玩家拆掉任意一块，这里就会返回 false。
     */
    public static boolean isStructureIntact(ServerLevel level, BlockPos origin, Direction front) {
        for (BlockPos pos : portalBlocks(origin, front)) {
            if (!level.getBlockState(pos).is(EnderPortal.REMOTE_PORTAL.get())) {
                return false;
            }
        }
        for (int lx = 0; lx < FRAME_WIDTH; lx++) {
            if (!level.getBlockState(localPos(origin, front, lx, 0, 0)).is(Blocks.OBSIDIAN)) {
                return false;
            }
            if (!level.getBlockState(localPos(origin, front, lx, FRAME_HEIGHT - 1, 0)).is(Blocks.OBSIDIAN)) {
                return false;
            }
        }
        for (int ly = 1; ly < FRAME_HEIGHT - 1; ly++) {
            if (!level.getBlockState(localPos(origin, front, 0, ly, 0)).is(Blocks.OBSIDIAN)) {
                return false;
            }
            if (!level.getBlockState(localPos(origin, front, FRAME_WIDTH - 1, ly, 0)).is(Blocks.OBSIDIAN)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把整座门（门面 + 框架 + 高台 + 台阶）全部清成空气。
     *
     * <p>一律按<b>完整结构</b>的清单拆，即使这一端是紧凑版（地牢那端）也没关系：
     * 紧凑版的方块本来就是完整版的子集，多出来的高台 / 台阶位置在地牢里是圆石，
     * 会被 {@link #isOwnBlock} 过滤掉，不会误伤。</p>
     *
     * @param skip 正在被移除的方块（重入时跳过，避免在同一位置二次 setBlock）
     */
    public static void destroyStructure(ServerLevel level, BlockPos origin, Direction front, BlockPos skip) {
        ensureChunks(level, origin, front);

        BlockState air = Blocks.AIR.defaultBlockState();
        for (Piece piece : pieces(origin, front,
                EnderPortal.REMOTE_PORTAL.get().defaultBlockState(), true)) {
            BlockPos pos = piece.pos();
            if (skip != null && pos.equals(skip)) {
                continue;
            }
            if (isOwnBlock(level.getBlockState(pos))) {
                level.setBlockAndUpdate(pos, air);
            }
        }

        level.playSound(null, localPos(origin, front, 2, 2, 1), SoundEvents.PORTAL_TRAVEL,
                SoundSource.BLOCKS, 0.8F, 0.6F);
        level.sendParticles(ParticleTypes.PORTAL,
                localX(origin, front, 2.0D, 1.5D), origin.getY() + 2.0D, localZ(origin, front, 2.0D, 1.5D),
                40, 0.6D, 1.0D, 0.2D, 0.3D);
    }

    /** 只拆自己放下去的东西，别误伤玩家建筑。 */
    private static boolean isOwnBlock(BlockState state) {
        return state.is(EnderPortal.REMOTE_PORTAL.get())
                || state.is(Blocks.OBSIDIAN)
                || state.is(Blocks.POLISHED_BLACKSTONE_STAIRS);
    }

    private static void ensureChunks(ServerLevel level, BlockPos origin, Direction front) {
        BlockPos a = localPos(origin, front, -1, 0, -1);
        BlockPos b = localPos(origin, front, FRAME_WIDTH, 0, STEP_COUNT + 3);
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
    }

    /** 这一端第一个配置好的门方块实体；没有返回 {@code null}。 */
    private static RemotePortalBlockEntity firstPortal(ServerLevel level, BlockPos origin, Direction front) {
        for (BlockPos pos : portalBlocks(origin, front)) {
            if (level.getBlockEntity(pos) instanceof RemotePortalBlockEntity portal) {
                return portal;
            }
        }
        return null;
    }
}
