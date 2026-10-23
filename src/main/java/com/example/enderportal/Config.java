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
    public static final ModConfigSpec.IntValue MIN_DISTANCE;
    public static final ModConfigSpec.IntValue MAX_DISTANCE;
    public static final ModConfigSpec.IntValue AIM_DISTANCE;
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
                .comment("一对传送门可以往返几次，次数用尽后传送门失效。")
                .defineInRange("roundTrips", 2, 1, 32);

        MIN_DISTANCE = builder
                .comment("传送门另一端距离玩家最近多少格。")
                .defineInRange("minDistance", 256, 32, 20000);

        MAX_DISTANCE = builder
                .comment("传送门另一端距离玩家最远多少格。")
                .defineInRange("maxDistance", 2048, 64, 30000);

        AIM_DISTANCE = builder
                .comment("门开在视线瞄准的位置，这是瞄准的最大距离（格）。")
                .defineInRange("aimDistance", 24, 4, 64);

        builder.pop();
        SPEC = builder.build();
    }

    private Config() {
    }
}
