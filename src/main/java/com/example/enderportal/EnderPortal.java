package com.example.enderportal;

import com.example.enderportal.block.RemotePortalBlock;
import com.example.enderportal.block.RemotePortalBlockEntity;
import com.example.enderportal.item.AbstractPortalStoneItem;
import com.example.enderportal.item.DungeonPortalStoneItem;
import com.example.enderportal.item.PortalStoneItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 末影传送石 (Ender Portal Stone)
 *
 * <p>长按右键引导 4 秒，在玩家面前开启一座「台阶 + 高台 + 传送门」结构，同时在距离玩家
 * 256~2048 格的另一个生物群系中开启它的“另一端”。两座门互相连接，可以往返穿行。</p>
 *
 * <p>传送门失效（次数用尽 / 任意组成方块被破坏）时，两端整座门连高台和台阶
 * 一起被完全清除，不留遗迹。每次新开一对门，该玩家上一对还没失效的门会自动消散。
 * 传送石本身最多只能开门 {@link PortalStoneItem#MAX_USES} 次，用完后物品消失。</p>
 */
@Mod(EnderPortal.MODID)
public class EnderPortal {
    public static final String MODID = "enderportal";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    /**
     * 末影传送石：8 黑曜石 + 1 末影珍珠合成。
     * durability(N) 让"N 次开门"直接复用原版耐久机制，用满后物品自动销毁。
     */
    public static final DeferredItem<PortalStoneItem> PORTAL_STONE = ITEMS.registerItem(
            "portal_stone",
            PortalStoneItem::new,
            new Item.Properties().durability(AbstractPortalStoneItem.MAX_USES).rarity(Rarity.RARE));

    /**
     * 地牢传送石：配方是 8 块金锭 + 1 颗末影珍珠（见 data/enderportal/recipe）。
     * 和末影传送石共用引导 / 冷却 / 耐久机制，只是另一端落在附近的地牢里。
     */
    public static final DeferredItem<DungeonPortalStoneItem> DUNGEON_PORTAL_STONE = ITEMS.registerItem(
            "dungeon_portal_stone",
            DungeonPortalStoneItem::new,
            new Item.Properties().durability(AbstractPortalStoneItem.MAX_USES).rarity(Rarity.EPIC));

    /**
     * 传送门方块：无碰撞、自带光源、可破坏（破坏即让整对门失效），不掉落任何东西。
     * 硬度给得很低，方便玩家随时手动拆掉门。
     */
    public static final DeferredBlock<RemotePortalBlock> REMOTE_PORTAL = BLOCKS.registerBlock(
            "remote_portal",
            RemotePortalBlock::new,
            BlockBehaviour.Properties.of()
                    .noCollission()
                    .strength(0.3F)
                    .lightLevel(state -> 11)
                    .noOcclusion()
                    .noLootTable()
                    .pushReaction(PushReaction.BLOCK)
                    .sound(SoundType.GLASS));

    /** 传送门方块实体：保存这一端 / 另一端的坐标与剩余可用次数。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RemotePortalBlockEntity>> REMOTE_PORTAL_BE =
            BLOCK_ENTITIES.register("remote_portal",
                    () -> BlockEntityType.Builder
                            .of(RemotePortalBlockEntity::new, REMOTE_PORTAL.get())
                            .build(null));

    public EnderPortal(IEventBus modEventBus, ModContainer modContainer) {
        ITEMS.register(modEventBus);
        BLOCKS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);

        modEventBus.addListener(this::addCreative);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(new ItemStack(PORTAL_STONE.get()));
            event.accept(new ItemStack(DUNGEON_PORTAL_STONE.get()));
        }
    }
}
