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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerArmorInvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerInvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerOffhandInvWrapper;

/**
 * What players take out of and put into containers. Only the player's own clicks count: before
 * and after every click packet the items in the slots that are not the player's inventory are
 * counted, and the difference is added up for the menu; when it closes, the sum per kind of item
 * is logged at the menu's block. A furnace that smelts or a pipe that fills the chest while the
 * menu is open changes nothing between those two counts, so it is not blamed on the player.
 * <p>
 * The block is the block entity behind the menu's slots, or the block the player clicked in the
 * same moment the menu opened, when a block entity stands there. A menu from an item (a
 * backpack opened by key) has neither and is not logged: a rollback could only put its items
 * into some unrelated block.
 */
public final class Containers {

    private static final class Open {
        final AbstractContainerMenu menu;
        final String world;
        final BlockPos pos;
        final Map<Key, Integer> delta = new LinkedHashMap<>();
        Map<Key, Integer> before;

        Open(AbstractContainerMenu menu, String world, BlockPos pos) {
            this.menu = menu;
            this.world = world;
            this.pos = pos;
        }
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
        BlockPos pos = blockOf(player, menu);
        if (pos == null) {
            OPEN.remove(player.getUUID());
            return;
        }
        OPEN.put(player.getUUID(), new Open(menu, Track.world(player.level()), pos));
    }

    /** Before the server handles a click of the player in a menu. */
    public static void beforeClick(ServerPlayer player) {
        Open o = OPEN.get(player.getUUID());
        if (o != null && player.containerMenu == o.menu) o.before = count(player, o.menu);
    }

    /** After the click: what changed in the container's slots is the player's doing. */
    public static void afterClick(ServerPlayer player) {
        Open o = OPEN.get(player.getUUID());
        if (o == null || o.before == null) return;
        Map<Key, Integer> now = count(player, o.menu);
        for (Map.Entry<Key, Integer> e : now.entrySet()) {
            int d = e.getValue() - o.before.getOrDefault(e.getKey(), 0);
            if (d != 0) o.delta.merge(e.getKey(), d, Integer::sum);
        }
        for (Map.Entry<Key, Integer> e : o.before.entrySet()) {
            if (!now.containsKey(e.getKey())) o.delta.merge(e.getKey(), -e.getValue(), Integer::sum);
        }
        o.before = null;
    }

    public static void closed(ServerPlayer player, AbstractContainerMenu menu) {
        Open o = OPEN.get(player.getUUID());
        Db db = Chronik.db();
        if (o == null || (menu != null && o.menu != menu)) return;
        OPEN.remove(player.getUUID());
        if (db == null) return;
        String actor = ((Owned) player).chronik$actor();
        for (Map.Entry<Key, Integer> e : o.delta.entrySet()) {
            int delta = e.getValue();
            if (delta == 0) continue;
            Key k = e.getKey();
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

    /** The block behind a menu: a block entity among its slots, else the block clicked as it opened. */
    private static BlockPos blockOf(ServerPlayer player, AbstractContainerMenu menu) {
        for (Slot s : menu.slots) {
            if (own(player, s)) continue;
            if (s.container instanceof BlockEntity be && be.getLevel() == player.level()) return be.getBlockPos();
        }
        BlockPos clicked = Inspector.recentUse(player, 2);
        if (clicked != null && player.level().getBlockEntity(clicked) != null) return clicked;
        return null;
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
