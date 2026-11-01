package com.example.enderportal;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 通用配置（config/enderportal-common.toml）。
 * 所有数值都可以在游戏里用 /reload 或重启后生效。
 */
public final class Config {

    public static final ModConfigSpec.IntValue CHANNEL_SECONDS;
    public static final ModConfigSpec.IntValue COOLDOWN_SECONDS;
    public static final ModConfigSpec.IntValue ROUND_TRIPS;
    public static final ModConfigSpec.IntValue AIM_DISTANCE;
    public static final ModConfigSpec.IntValue DUNGEON_RADIUS_CHUNKS;

    public static final ModConfigSpec.IntValue TARGET_X;
    public static final ModConfigSpec.IntValue TARGET_Y;
    public static final ModConfigSpec.IntValue TARGET_Z;
    public static final ModConfigSpec.IntValue TARGET_SEARCH_RADIUS;

    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Ender Portal Stone").push("general");

        CHANNEL_SECONDS = builder
                .comment("使用传送石需要引导多少秒（长按右键，松开会中断）。")
                .defineInRange("channelSeconds", 4, 1, 30);

        COOLDOWN_SECONDS = builder
                .comment("使用传送石后的冷却时间（秒）。")
                .defineInRange("cooldownSeconds", 60, 0, 3600);

        ROUND_TRIPS = builder
                .comment("每个玩家可以往返几次。次数按人单独计算：",
                        "创造者用完自己的次数后整座门失效，其他玩家用完则不能再传送。")
                .defineInRange("roundTrips", 2, 1, 32);

        AIM_DISTANCE = builder
                .comment("门开在视线瞄准的位置，这是瞄准的最大距离（格）。")
                .defineInRange("aimDistance", 24, 4, 64);

        DUNGEON_RADIUS_CHUNKS = builder
                .comment("地牢传送石在玩家周围多少个区块内找地牢（1 区块 = 16 格）。",
                        "只有已经加载的区块才能被扫到，所以实际范围还受视野/模拟距离限制。")
                .defineInRange("dungeonRadiusChunks", 20, 1, 48);

        builder.pop();

        builder.comment("末影传送石：另一端的固定坐标").push("destination");

        TARGET_X = builder
                .comment("另一端的 X 坐标（整数方块坐标）。",
                        "三个坐标一起决定传送门开在哪；改完重启或用 /reload 生效。")
                .defineInRange("targetX", 1000, -30000000, 30000000);

        TARGET_Y = builder
                .comment("另一端的 Y 坐标。",
                        "这里的 Y 只当作参考高度：这一列上下会找一块真正站得住的地面来放门，",
                        "所以填地面附近的高度就行，填到半空也不会把门架在天上。")
                .defineInRange("targetY", 64, -64, 320);

        TARGET_Z = builder
                .comment("另一端的 Z 坐标（整数方块坐标）。")
                .defineInRange("targetZ", 1000, -30000000, 30000000);

        TARGET_SEARCH_RADIUS = builder
                .comment("坐标那一带放不下整座门时，向周围找空地的最大半径（格）。",
                        "山地、水面、树很多的地方会需要一点余量；设 0 表示只认那一个点。")
                .defineInRange("targetSearchRadius", 16, 0, 128);

        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }
}
