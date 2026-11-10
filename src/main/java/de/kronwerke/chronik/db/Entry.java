package de.kronwerke.chronik.db;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One thing that happened, as the server thread hands it to the writer. Everything in here is
 * immutable or a private copy, so the writer thread can serialize it later without touching the
 * world.
 */
public final class Entry {

    public static final int BREAK = 0, PLACE = 1, CHANGE = 2, TAKE = 3, PUT = 4, KILL = 5, DROP = 6, PICKUP = 7;
    public static final int CHAT = 10, COMMAND = 11, JOIN = 12, LEAVE = 13;

    public final long time;
    public final int action;
    public final String world;
    public final int x, y, z;
    public final String actor;
    public final String cause;
    /** Blocks: the state before and after, and the block entity before (break or change), may be null. */
    public final BlockState before, after;
    public final CompoundTag blockEntity;
    /** Containers, drops, pickups: the item (one of it, with its components) and how many. */
    public final ItemStack item;
    public final CompoundTag itemTag;
    public final int amount;
    /** Kills: the entity type; chat, commands, sessions: the text. */
    public final String text;

    private Entry(int action, String world, int x, int y, int z, String actor, String cause, BlockState before, BlockState after,
                  CompoundTag blockEntity, ItemStack item, CompoundTag itemTag, int amount, String text) {
        this.time = System.currentTimeMillis();
        this.action = action;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.actor = actor;
        this.cause = cause;
        this.before = before;
        this.after = after;
        this.blockEntity = blockEntity;
        this.item = item;
        this.itemTag = itemTag;
        this.amount = amount;
        this.text = text;
    }

    public static Entry block(int action, String world, int x, int y, int z, String actor, String cause,
                              BlockState before, BlockState after, CompoundTag blockEntity) {
        return new Entry(action, world, x, y, z, actor, cause, before, after, blockEntity, null, null, 0, null);
    }

    public static Entry item(int action, String world, int x, int y, int z, String actor, String cause, ItemStack one, CompoundTag tag, int amount) {
        return new Entry(action, world, x, y, z, actor, cause, null, null, null, one, tag, amount, null);
    }

    public static Entry kill(String world, int x, int y, int z, String actor, String cause, String victim) {
        return new Entry(KILL, world, x, y, z, actor, cause, null, null, null, null, null, 0, victim);
    }

    public static Entry message(int action, String world, int x, int y, int z, String actor, String text) {
        return new Entry(action, world, x, y, z, actor, null, null, null, null, null, null, 0, text);
    }

    public boolean isMessage() {
        return action >= CHAT;
    }
}
