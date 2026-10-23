package com.example.enderportal.client;

import com.example.enderportal.EnderPortal;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 客户端专用初始化。
 *
 * <p>用 {@code @EventBusSubscriber(value = Dist.CLIENT)} 标注，NeoForge 在专用服务器上
 * 不会加载这个类，所以引用客户端类（{@link ChannelHud}、{@code GuiGraphics} 等）
 * 不会导致服务端崩溃。</p>
 */
@EventBusSubscriber(modid = EnderPortal.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.EXPERIENCE_BAR,
                ResourceLocation.fromNamespaceAndPath(EnderPortal.MODID, "channel_bar"),
                new ChannelHud());
    }
}
