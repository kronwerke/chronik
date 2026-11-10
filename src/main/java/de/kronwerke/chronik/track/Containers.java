package de.kronwerke.chronik.track;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import de.kronwerke.chronik.Chronik;
import de.kronwerke.chronik.db.Db;
import de.kronwerke.chronik.db.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerArmorInvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerInvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerOffhandInvWrapper;

/**
 * What players take out of and put into containers. When a menu opens, the items in every slot
 * that is not the player's own inventory are counted; when it closes they are counted again,
 * and the difference per kind of item is logged at the block the player opened. This works for
 * any modded container whose menu uses ordinary slots or item handler slots.
 */
public final class Containers {

    private record Open(AbstractContainerMenu menu, String world, BlockPos pos, Map<Key, Integer> counts) {
    }

    /** An item kind: the item and its components, without the count. */
    private record Key(ItemStack one) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && ItemStack.isSameItemSameComponents(one, k.one);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(one);
        }
    }

    private static final Map<UUID, Open> OPEN = new HashMap<>();

    private Containers() {
    }

    public static void opened(ServerPlayer player, AbstractContainerMenu menu) {
        if (Chronik.db() == null || menu == player.inventoryMenu) return;
        BlockPos pos = Inspector.recentUse(player);
        if (pos == null) pos = player.blockPosition();
        OPEN.put(player.getUUID(), new Open(menu, Track.world(player.level()), pos, count(player, menu)));
    }

    public static void closed(ServerPlayer player, AbstractContainerMenu menu) {
        Open o = OPEN.remove(player.getUUID());
        Db db = Chronik.db();
        if (o == null || db == null || (menu != null && o.menu != menu)) return;
        Map<Key, Integer> now = count(player, o.menu);
        Map<Key, Integer> all = new LinkedHashMap<>(o.counts);
        now.keySet().forEach(k -> all.putIfAbsent(k, 0));
        String actor = ((Owned) player).chronik$actor();
        for (Key k : all.keySet()) {
            int delta = now.getOrDefault(k, 0) - o.counts.getOrDefault(k, 0);
            if (delta == 0) continue;
            CompoundTag tag = null;
            try {
                Tag t = k.one.save(player.registryAccess());
                if (t instanceof CompoundTag c) tag = c;
            } catch (RuntimeException ignored) {
            }
            db.add(Entry.item(delta > 0 ? Entry.PUT : Entry.TAKE, o.world, o.pos.getX(), o.pos.getY(), o.pos.getZ(), actor, null,
                    k.one, tag, Math.abs(delta)));
        }
    }

    /** On logout or when the server stops: a container still open counts as closed. */
    public static void left(ServerPlayer player) {
        closed(player, null);
    }

    private static Map<Key, Integer> count(ServerPlayer player, AbstractContainerMenu menu) {
        Map<Key, Integer> out = new HashMap<>();
        for (Slot s : menu.slots) {
            if (own(player, s)) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            out.merge(new Key(st.copyWithCount(1)), st.getCount(), Integer::sum);
        }
        return out;
    }

    /** A slot of the player's own inventory (vanilla slot or an item handler over it). */
    private static boolean own(ServerPlayer player, Slot s) {
        if (s.container == player.getInventory()) return true;
        if (s instanceof SlotItemHandler h) {
            IItemHandler ih = h.getItemHandler();
            return ih instanceof PlayerInvWrapper || ih instanceof PlayerMainInvWrapper || ih instanceof PlayerArmorInvWrapper
                    || ih instanceof PlayerOffhandInvWrapper || (ih instanceof InvWrapper w && w.getInv() == player.getInventory());
        }
        return false;
    }
}
