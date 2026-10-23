package com.example.enderportal.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 在玩家附近找「地牢」（dungeon / monster room）。
 *
 * <p>注意：原版地牢<b>不是结构（structure），而是一个地物（feature）</b>
 * （{@code MonsterRoomFeature}），所以 {@code ServerLevel#findNearestMapStructure}
 * 找不到它 —— 那套 API 只认要塞、矿井、村庄这类结构。地牢没有索引，
 * 只能翻区块找它留下的痕迹。</p>
 *
 * <p>这里用的判定是「刷怪笼 + 圆石地板」：地牢中央一定有一个刷怪笼，
 * 而它脚下的地板被铺成圆石 / 苔石。要塞是石砖 + 蠹虫刷怪笼、
 * 废弃矿井是木板 + 铁轨、下界要塞是下界砖，都不会命中这条规则。</p>
 *
 * <p>只扫描<b>已经加载</b>的区块（{@code getChunkNow}），不会为了找地牢去
 * 同步生成新地形，所以这个操作不会卡服。</p>
 */
public final class DungeonFinder {

    /** 地板范围内至少要有这么多块圆石/苔石，才算地牢。 */
    private static final int MIN_FLOOR_TILES = 6;

    private DungeonFinder() {
    }

    /**
     * 找离 {@code center} 最近的若干个地牢刷怪笼，按距离由近到远排序。
     *
     * <p>按区块一圈圈往外扫，扫到第一圈有结果的就不再往外找，
     * 所以返回的都是"最近那一圈"里的。多返回几个是为了容错：
     * 最近那个地牢可能四面都没有合适的墙、或者玩家自己就站在里面，
     * 这时可以换下一个。</p>
     *
     * @param radiusChunks 最多往外找多少个区块
     * @param limit        最多返回几个候选
     * @return 刷怪笼坐标列表；一个都没找到时返回空列表
     */
    public static List<BlockPos> findDungeons(ServerLevel level, BlockPos center,
                                              int radiusChunks, int limit) {
        ServerChunkCache cache = level.getChunkSource();
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;

        List<BlockPos> found = new ArrayList<>(limit);

        for (int ring = 0; ring <= radiusChunks && found.size() < limit; ring++) {
            boolean foundInRing = false;
            for (int chunkX = centerChunkX - ring; chunkX <= centerChunkX + ring; chunkX++) {
                for (int chunkZ = centerChunkZ - ring; chunkZ <= centerChunkZ + ring; chunkZ++) {
                    // 只走这一圈的边框，内圈上一轮已经看过了
                    if (Math.max(Math.abs(chunkX - centerChunkX), Math.abs(chunkZ - centerChunkZ)) != ring) {
                        continue;
                    }
                    LevelChunk chunk = cache.getChunkNow(chunkX, chunkZ);
                    if (chunk == null) {
                        continue; // 没加载，跳过（不为它生成地形）
                    }
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        if (!(blockEntity instanceof SpawnerBlockEntity)) {
                            continue;
                        }
                        BlockPos pos = blockEntity.getBlockPos();
                        if (!isDungeonRoom(level, pos)) {
                            continue;
                        }
                        if (!found.contains(pos)) {
                            found.add(pos);
                            foundInRing = true;
                        }
                    }
                }
            }
            if (foundInRing) {
                break;
            }
        }

        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        if (found.size() > limit) {
            return new ArrayList<>(found.subList(0, limit));
        }
        return found;
    }

    /** 刷怪笼脚下的地板是不是圆石 / 苔石。 */
    private static boolean isDungeonRoom(ServerLevel level, BlockPos spawner) {
        int tiles = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockState floor = level.getBlockState(spawner.offset(dx, -1, dz));
                if (floor.is(Blocks.COBBLESTONE) || floor.is(Blocks.MOSSY_COBBLESTONE)) {
                    tiles++;
                }
            }
        }
        return tiles >= MIN_FLOOR_TILES;
    }
}
