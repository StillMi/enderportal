package com.example.enderportal.client;

import net.minecraft.world.InteractionHand;

/**
 * 通用代码调用客户端功能的唯一入口。
 *
 * <p>{@code AbstractPortalStoneItem#use} 是两边都会跑的方法，里面要"潜行右键打开坐标界面"。
 * 界面本身（{@link PortalTargetScreen}）会碰 {@code Minecraft.getInstance()} 这类
 * 只有客户端才有的东西，所以隔了一层：</p>
 *
 * <ul>
 *   <li>调用点先用 {@code FMLEnvironment.dist == Dist.CLIENT} 判断，
 *       专用服务器上这个方法体根本不会执行；</li>
 *   <li>这个类本身<b>不加</b> {@code @OnlyIn}。加了反而更容易在类校验阶段被提前加载，
 *       不加就是普通的类，只有在真被调用时 JVM 才会去解析它引用的客户端类型。</li>
 * </ul>
 *
 * <p>也就是说：真正危险的那一步（加载 {@link PortalTargetScreen}）只会在
 * "客户端 + 玩家真的潜行右键了" 的时候发生。</p>
 */
public final class ClientHooks {

    private ClientHooks() {
    }

    /** 打开目标坐标输入界面。只应在客户端调用。 */
    public static void openTargetScreen(InteractionHand hand) {
        PortalTargetScreen.open(hand);
    }
}
