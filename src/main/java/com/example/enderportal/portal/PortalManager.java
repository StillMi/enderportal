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
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

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

    /** 生成时玩家与门之间的距离，玩家正好站在最外侧台阶前。 */
    private static final int APPROACH_DISTANCE = STEP_COUNT + 3;

    /**
     * 拆门重入保护：拆除过程中 setBlock 会再次触发 onRemove，
     * 没有它就会无限递归。方块更新只在服务端主线程发生，普通静态量足够。
     */
    private static boolean tearingDown = false;

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

    /**
     * 在玩家面前开一对门：一座在玩家前方（台阶朝向玩家），另一座在别的生物群系。
     * 开门前会先把该玩家上一对还没失效的门清掉。
     *
     * @return 是否成功
     */
    public static boolean openPortalPair(ServerLevel level, ServerPlayer player) {
        Direction front = player.getDirection().getOpposite();
        Direction lateral = front.getCounterClockWise();

        // 结构沿 front 方向朝玩家延伸（台阶在门与玩家之间）；
        // 再横向偏移一格，让 4 格宽的框架正好以玩家为中心
        BlockPos probe = player.blockPosition()
                .relative(front.getOpposite(), APPROACH_DISTANCE)
                .offset(-lateral.getStepX(), 0, -lateral.getStepZ());
        int[][] surfaces = footprintSurfaces(level, probe, front);
        int ground = Mth.clamp(minOf(surfaces), player.blockPosition().getY() - 2,
                player.blockPosition().getY() + 1);
        BlockPos origin = new BlockPos(probe.getX(), ground + BASE_OFFSET, probe.getZ());

        if (!hasRoom(level, origin, front)) {
            return false;
        }

        BlockPos target = findTarget(level, player.blockPosition(), front);
        if (target == null) {
            return false;
        }

        // 先把上一对门清掉
        closePrevious(level, player);

        int uses = Math.max(2, Config.ROUND_TRIPS.get() * 2);
        placeStructure(level, origin, front, target, front, uses);
        placeStructure(level, target, front, origin, front, uses);

        PortalRegistry.get(level.getServer()).remember(player.getUUID(),
                new PortalRegistry.Entry(level.dimension(), origin, front, target, front));

        Component biomeName = biomeName(level, target);
        player.displayClientMessage(Component.translatable("message.enderportal.created",
                biomeName, uses / 2).withStyle(ChatFormatting.LIGHT_PURPLE), false);

        return true;
    }

    /** 让该玩家上一对门失效（如果还在的话）。 */
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
        boolean stillThere = isStructureIntact(other, previous.origin(), previous.front());
        invalidate(other, previous.origin(), previous.front(), previous.partner(), previous.partnerFront());
        if (stillThere) {
            player.displayClientMessage(Component.translatable("message.enderportal.previous_closed")
                    .withStyle(ChatFormatting.DARK_PURPLE), true);
        }
    }

    /** 清掉落点上的实心方块，防止传送进墙里。 */
    public static void clearLandingSpace(ServerLevel level, BlockPos feet) {
        for (int i = 0; i < 2; i++) {
            BlockPos pos = feet.above(i);
            if (!level.getBlockState(pos).isAir()) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /**
     * 消耗一次使用次数；次数归零时两端整座门一起消失。
     *
     * @return 剩余次数
     */
    public static int consumeUse(ServerLevel level, BlockPos origin, Direction front,
                                 BlockPos partner, Direction partnerFront) {
        int left = Math.max(0, getRemaining(level, origin, front) - 1);
        if (left <= 0) {
            invalidate(level, origin, front, partner, partnerFront);
            return 0;
        }
        setRemaining(level, origin, front, left);
        setRemaining(level, partner, partnerFront, left);
        return left;
    }

    /**
     * 让一对传送门彻底失效：两端所有组成方块（门面 + 框架 + 高台 + 台阶）全部清除。
     */
    public static void invalidate(ServerLevel level, BlockPos origin, Direction front,
                                  BlockPos partner, Direction partnerFront) {
        if (tearingDown) {
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

    public static boolean isTearingDown() {
        return tearingDown;
    }

    /* ------------------------------------------------------------------ */
    /* 地形 / 生物群系 / 落点搜索                                            */
    /* ------------------------------------------------------------------ */

    /** 结构占地范围内所有列的地表高度（局部 lx=-1..4, lz=-1..5）。 */
    private static int[][] footprintSurfaces(ServerLevel level, BlockPos origin, Direction front) {
        int[][] surfaces = new int[FRAME_WIDTH + 2][STEP_COUNT + 4];
        for (int ix = 0; ix < surfaces.length; ix++) {
            for (int iz = 0; iz < surfaces[ix].length; iz++) {
                BlockPos pos = localPos(origin, front, ix - 1, 0, iz - 1);
                surfaces[ix][iz] = level.getHeightmapPos(
                        Heightmap.Types.WORLD_SURFACE, new BlockPos(pos.getX(), 0, pos.getZ())).getY();
            }
        }
        return surfaces;
    }

    private static int minOf(int[][] values) {
        int min = Integer.MAX_VALUE;
        for (int[] row : values) {
            for (int value : row) {
                min = Math.min(min, value);
            }
        }
        return min;
    }

    private static int maxOf(int[][] values) {
        int max = Integer.MIN_VALUE;
        for (int[] row : values) {
            for (int value : row) {
                max = Math.max(max, value);
            }
        }
        return max;
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

    /** 找另一座门的位置；找不到返回 null。 */
    private static BlockPos findTarget(ServerLevel level, BlockPos playerPos, Direction front) {
        ResourceKey<Biome> currentKey = level.getBiome(playerPos).unwrapKey().orElse(null);

        RandomSource random = level.getRandom();
        int minDistance = Math.max(32, Config.MIN_DISTANCE.get());
        int maxDistance = Math.max(minDistance + 64, Config.MAX_DISTANCE.get());

        int generated = 0;
        for (int attempt = 0; attempt < 64 && generated < 6; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = minDistance + random.nextInt(maxDistance - minDistance);
            int x = playerPos.getX() + (int) Math.round(Math.cos(angle) * (double) distance);
            int z = playerPos.getZ() + (int) Math.round(Math.sin(angle) * (double) distance);

            int probeY = Mth.clamp(64, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);

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

            // 命中不同群系了，才真正生成地形
            level.getChunk(x >> 4, z >> 4);
            generated++;

            BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z));
            if (!level.getFluidState(surface).isEmpty()) {
                continue; // 水面 / 岩浆面
            }
            BlockPos below = surface.below();
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                continue; // 树叶、浮冰之类
            }

            // 让这一列落在高台中央（局部 x=2, z=1）
            Direction lateral = front.getCounterClockWise();
            BlockPos probe = new BlockPos(
                    surface.getX() - lateral.getStepX() * 2 - front.getStepX(),
                    0,
                    surface.getZ() - lateral.getStepZ() * 2 - front.getStepZ());

            int[][] surfaces = footprintSurfaces(level, probe, front);
            if (maxOf(surfaces) - minOf(surfaces) > 3) {
                continue; // 地形起伏太大，高台会很难看
            }
            BlockPos origin = new BlockPos(probe.getX(), minOf(surfaces) + BASE_OFFSET, probe.getZ());
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
     */
    private static void placeStructure(ServerLevel level, BlockPos origin, Direction front,
                                       BlockPos partnerOrigin, Direction partnerFront, int uses) {
        ensureChunks(level, origin, front);

        // 1) 挖空：门洞 + 高台上方
        BlockState air = Blocks.AIR.defaultBlockState();
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

        // 2) 摆放
        BlockState portalState = EnderPortal.REMOTE_PORTAL.get().defaultBlockState();
        for (Piece piece : pieces(origin, front, portalState)) {
            level.setBlockAndUpdate(piece.pos(), piece.state());
            if (piece.state().is(EnderPortal.REMOTE_PORTAL.get())
                    && level.getBlockEntity(piece.pos()) instanceof RemotePortalBlockEntity portal) {
                portal.configure(origin, front, partnerOrigin, partnerFront, uses);
            }
        }

        level.playSound(null, localPos(origin, front, 2, 2, 1), SoundEvents.PORTAL_AMBIENT,
                SoundSource.BLOCKS, 0.6F, 1.2F);
    }

    /**
     * 整座门要放的所有方块（含支撑填充）。
     * 坐标只由 origin / front 决定，所以搭建和拆除共用这一份清单，保证拆得干净。
     */
    private static List<Piece> pieces(BlockPos origin, Direction front, BlockState portalState) {
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
     * @param skip 正在被移除的方块（重入时跳过，避免在同一位置二次 setBlock）
     */
    public static void destroyStructure(ServerLevel level, BlockPos origin, Direction front, BlockPos skip) {
        ensureChunks(level, origin, front);

        BlockState air = Blocks.AIR.defaultBlockState();
        for (Piece piece : pieces(origin, front, EnderPortal.REMOTE_PORTAL.get().defaultBlockState())) {
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

    public static int getRemaining(ServerLevel level, BlockPos origin, Direction front) {
        for (BlockPos pos : portalBlocks(origin, front)) {
            if (level.getBlockEntity(pos) instanceof RemotePortalBlockEntity portal) {
                return portal.getRemaining();
            }
        }
        return 0;
    }

    public static void setRemaining(ServerLevel level, BlockPos origin, Direction front, int remaining) {
        ensureChunks(level, origin, front);
        for (BlockPos pos : portalBlocks(origin, front)) {
            if (level.getBlockEntity(pos) instanceof RemotePortalBlockEntity portal) {
                portal.setRemaining(remaining);
            }
        }
    }
}
