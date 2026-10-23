package com.example.enderportal.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 记录每个玩家"上一对传送门"的位置。
 *
 * <p>需求：每次新开一座门，上一个还没失效的门要自动消散。数据存在主世界的
 * {@code data/enderportal_portals.dat} 里，所以跨维度也管用（玩家换维度开门，
 * 之前那个维度里的门一样会被清掉）。</p>
 */
public class PortalRegistry extends SavedData {

    private static final String DATA_NAME = "enderportal_portals";
    private static final String KEY_ENTRIES = "Entries";

    /** 一座门对的完整描述。 */
    public record Entry(ResourceKey<Level> dimension, BlockPos origin, Direction front,
                        BlockPos partner, Direction partnerFront) {
    }

    private final Map<UUID, Entry> lastPortal = new HashMap<>();

    public static PortalRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PortalRegistry::new, PortalRegistry::load, DataFixTypes.LEVEL),
                DATA_NAME);
    }

    public Entry get(UUID player) {
        return lastPortal.get(player);
    }

    public void remember(UUID player, Entry entry) {
        lastPortal.put(player, entry);
        setDirty();
    }

    public void forget(UUID player) {
        if (lastPortal.remove(player) != null) {
            setDirty();
        }
    }

    public static PortalRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        PortalRegistry registry = new PortalRegistry();
        ListTag entries = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.hasUUID("Player")) {
                continue;
            }
            ResourceLocation dimensionId = ResourceLocation.tryParse(entry.getString("Dimension"));
            if (dimensionId == null) {
                continue;
            }
            registry.lastPortal.put(entry.getUUID("Player"), new Entry(
                    ResourceKey.create(Registries.DIMENSION, dimensionId),
                    BlockPos.of(entry.getLong("Origin")),
                    Direction.from2DDataValue(entry.getInt("Front")),
                    BlockPos.of(entry.getLong("Partner")),
                    Direction.from2DDataValue(entry.getInt("PartnerFront"))));
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        lastPortal.forEach((player, entry) -> {
            CompoundTag data = new CompoundTag();
            data.putUUID("Player", player);
            data.putString("Dimension", entry.dimension().location().toString());
            data.putLong("Origin", entry.origin().asLong());
            data.putInt("Front", entry.front().get2DDataValue());
            data.putLong("Partner", entry.partner().asLong());
            data.putInt("PartnerFront", entry.partnerFront().get2DDataValue());
            entries.add(data);
        });
        tag.put(KEY_ENTRIES, entries);
        return tag;
    }
}
