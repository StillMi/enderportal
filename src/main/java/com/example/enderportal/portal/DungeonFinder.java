package com.example.enderportal.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 在玩家附近找「地牢」（dungeon / monster room）。
 *
 * <p>注意：原版地牢<b>不是结构（structure），而是一个地物（feature）</b>
 * （{@code MonsterRoomFeature}），所以 {@code ServerLevel#findNearestMapStructure}
 * 找不到它 —— 那套 API 只认要塞、矿井、村庄这类结构。地牢没有索引，
 * 只能翻区块找它留下的痕迹。</p>
 *
 * <p>这里用的判定是「刷怪笼 + 圆石地板」：地牢中央一定有一个刷怪笼，
 * 而它脚下的 5×5 地板被铺成圆石 / 苔石。要塞是石砖 + 蠹虫刷怪笼、
 * 废弃矿井是木板 + 铁轨、下界要塞是下界砖，都不会命中这条规则。</p>
 *
 * <p>只扫描<b>已经加载</b>的区块（{@code getChunkNow}），不会为了找地牢去
 * 同步生成新地形，所以这个操作不会卡服。视野距离内的区块足够找到地牢。</p>
 */
public final class DungeonFinder {

    /** 地板范围内至少要有这么多块圆石/苔石，才算地牢。 */
    private static final int MIN_FLOOR_TILES = 6;

    private DungeonFinder() {
    }

    /**
     * 找离 {@code center} 最近的地牢刷怪笼。
     *
     * <p>按区块一圈圈往外扫，扫到第一圈有结果的就不再往外找，
     * 所以返回的一定是"最近的那一圈"里最近的。</p>
     *
     * @param radiusChunks 最多往外找多少个区块
     * @return 刷怪笼的坐标；没找到返回 {@code null}
     */
    public static BlockPos findNearestSpawner(ServerLevel level, BlockPos center, int radiusChunks) {
        ServerChunkCache cache = level.getChunkSource();
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int ring = 0; ring <= radiusChunks; ring++) {
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
                        double distance = pos.distSqr(center);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = pos;
                            foundInRing = true;
                        }
                    }
                }
            }
            if (foundInRing) {
                break;
            }
        }
        return best;
    }

    /** 刷怪笼脚下的 5×5 地板是不是圆石 / 苔石。 */
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
