package de.kronwerke.chronik.track;

import de.kronwerke.chronik.Chronik;
import de.kronwerke.chronik.ChronikConfig;
import de.kronwerke.chronik.cmd.ChronikCommand;
import de.kronwerke.chronik.db.Db;
import de.kronwerke.chronik.db.Entry;
import de.kronwerke.chronik.db.Filter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Everything Chronik learns from NeoForge events rather than from its hooks. */
public final class Events {

    private Events() {
    }

    public static void register() {
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(Events::onJoinLevel);
        bus.addListener(Events::onOpen);
        bus.addListener(Events::onClose);
        bus.addListener(Events::onDeath);
        bus.addListener(Events::onToss);
        bus.addListener(Events::onPickup);
        bus.addListener(Events::onChat);
        bus.addListener(Events::onCommand);
        bus.addListener(Events::onLogin);
        bus.addListener(Events::onLogout);
        bus.addListener(Events::onBreak);
        bus.addListener(Events::onPlace);
        bus.addListener(Events::onLeftClick);
        bus.addListener(Events::onRightClick);
    }

    // ---- ownership ----

    /**
     * Something new appears while someone acts: TNT a player lit, an arrow, a falling block, a
     * Create contraption a machine assembled, a bomb. It acts for that player from now on. Mobs
     * keep acting as themselves (a zombie from a player's spawner is still a zombie), and items
     * and experience are left alone so they do not all carry an owner.
     */
    private static void onJoinLevel(EntityJoinLevelEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) Actors.joined(sp); // a load test bot may skip the login event
        if (e.loadedFromDisk() || e.getLevel().isClientSide()) return;
        Entity entity = e.getEntity();
        if (entity instanceof Player || entity instanceof Mob || entity instanceof ItemEntity || entity instanceof ExperienceOrb) return;
        String actor = Ctx.actor();
        if (!Actors.isPlayer(actor)) return;
        Owned o = (Owned) entity;
        if (o.chronik$owner() == null) o.chronik$setOwner(actor);
    }

    // ---- containers, kills, items ----

    private static void onOpen(PlayerContainerEvent.Open e) {
        if (e.getEntity() instanceof ServerPlayer p && !(p instanceof FakePlayer)) Containers.opened(p, e.getContainer());
    }

    private static void onClose(PlayerContainerEvent.Close e) {
        if (e.getEntity() instanceof ServerPlayer p && !(p instanceof FakePlayer)) Containers.closed(p, e.getContainer());
    }

    private static void onDeath(LivingDeathEvent e) {
        Db db = Chronik.db();
        if (db == null || !ChronikConfig.LOG_KILLS.get() || e.getEntity().level().isClientSide()) return;
        Entity killer = e.getSource().getEntity();
        String actor = killer != null ? Actors.of(killer) : Ctx.actor();
        if (!Actors.isPlayer(actor)) return;
        String cause = killer != null ? ((Owned) killer).chronik$cause() : Ctx.cause();
        Entity victim = e.getEntity();
        String what = victim instanceof Player pl ? pl.getGameProfile().getName() : Actors.type(victim.getType());
        BlockPos pos = victim.blockPosition();
        db.add(Entry.kill(Track.world(victim.level()), pos.getX(), pos.getY(), pos.getZ(), actor, cause, what));
    }

    private static void onToss(ItemTossEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p && !(p instanceof FakePlayer)) item(p, Entry.DROP, e.getEntity().getItem(), e.getEntity().blockPosition());
    }

    private static void onPickup(ItemEntityPickupEvent.Post e) {
        if (e.getPlayer() instanceof ServerPlayer p && !(p instanceof FakePlayer)) {
            ItemStack orig = e.getOriginalStack();
            int took = orig.getCount() - e.getCurrentStack().getCount();
            if (took > 0) pickup(p, orig.copyWithCount(took), e.getItemEntity().blockPosition());
        }
    }

    /** Pickups waiting to be merged: per player and kind of item, the first place and the count so far. */
    private static final class Pending {
        final ServerPlayer player;
        final ItemStack one;
        final BlockPos pos;
        final long until;
        int count;

        Pending(ServerPlayer player, ItemStack one, BlockPos pos, long until) {
            this.player = player;
            this.one = one;
            this.pos = pos;
            this.until = until;
        }
    }

    private static final java.util.Map<String, Pending> PICKUPS = new java.util.LinkedHashMap<>();

    /** A magnet or a mob farm picks up hundreds of stacks a minute: one entry per player and item over a few seconds. */
    private static void pickup(ServerPlayer p, ItemStack stack, BlockPos pos) {
        int secs = ChronikConfig.PICKUP_MERGE_SECONDS.get();
        if (secs <= 0) {
            item(p, Entry.PICKUP, stack, pos);
            return;
        }
        String key = p.getUUID() + "|" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + ItemStack.hashItemAndComponents(stack);
        Pending pend = PICKUPS.get(key);
        if (pend == null || !ItemStack.isSameItemSameComponents(pend.one, stack)) {
            if (pend != null) flush(pend);
            pend = new Pending(p, stack.copyWithCount(1), pos, System.currentTimeMillis() + secs * 1000L);
            PICKUPS.put(key, pend);
        }
        pend.count += stack.getCount();
    }

    /** Every tick: merged pickups whose time is up are written. */
    public static void flushPickups(boolean all) {
        if (PICKUPS.isEmpty()) return;
        long now = System.currentTimeMillis();
        var it = PICKUPS.values().iterator();
        while (it.hasNext()) {
            Pending pend = it.next();
            if (all || now >= pend.until || pend.player.hasDisconnected()) {
                flush(pend);
                it.remove();
            }
        }
    }

    private static void flush(Pending pend) {
        item(pend.player, Entry.PICKUP, pend.one.copyWithCount(pend.count), pend.pos);
    }

    private static void item(ServerPlayer p, int action, ItemStack stack, BlockPos pos) {
        Db db = Chronik.db();
        if (db == null || stack.isEmpty() || !ChronikConfig.LOG_ITEMS.get()) return;
        CompoundTag tag = null;
        try {
            Tag t = stack.copyWithCount(1).save(p.registryAccess());
            if (t instanceof CompoundTag c) tag = c;
        } catch (RuntimeException ignored) {
        }
        db.add(Entry.item(action, Track.world(p.level()), pos.getX(), pos.getY(), pos.getZ(), ((Owned) p).chronik$actor(), null,
                stack.copyWithCount(1), tag, stack.getCount()));
    }

    // ---- chat, commands, sessions ----

    private static void onChat(ServerChatEvent e) {
        if (ChronikConfig.LOG_CHAT.get()) message(e.getPlayer(), Entry.CHAT, e.getRawText());
    }

    private static void onCommand(CommandEvent e) {
        if (!ChronikConfig.LOG_COMMANDS.get()) return;
        if (e.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer p && !(p instanceof FakePlayer)) {
            message(p, Entry.COMMAND, "/" + privateCut(e.getParseResults().getReader().getString()));
        }
    }

    /** A direct message keeps its command and receiver; its text is nobody's business (log.privateCommands). */
    static String privateCut(String command) {
        String c = command.startsWith("/") ? command.substring(1) : command;
        String[] parts = c.split(" ", 3);
        String name = parts[0].toLowerCase(java.util.Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1); // minecraft:msg
        if (!ChronikConfig.PRIVATE_COMMANDS.get().contains(name)) return c;
        boolean hasReceiver = !name.equals("r") && !name.equals("reply") && !name.equals("teammsg") && !name.equals("tm");
        return parts[0] + (hasReceiver && parts.length > 1 ? " " + parts[1] : "") + " (Text nicht gespeichert)";
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        Actors.joined(p);
        if (Actors.bot(((Owned) p).chronik$actor())) return;
        Db db = Chronik.db();
        if (db != null) db.name(((Owned) p).chronik$actor(), p.getGameProfile().getName());
        if (ChronikConfig.LOG_SESSIONS.get()) message(p, Entry.JOIN, "");
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        Containers.left(p);
        Inspector.left(p);
        if (ChronikConfig.LOG_SESSIONS.get()) message(p, Entry.LEAVE, "");
        Actors.left(p);
    }

    private static void message(ServerPlayer p, int kind, String text) {
        Db db = Chronik.db();
        if (db == null || p instanceof FakePlayer) return;
        BlockPos pos = p.blockPosition();
        db.add(Entry.message(kind, Track.world(p.level()), pos.getX(), pos.getY(), pos.getZ(), ((Owned) p).chronik$actor(), text));
    }

    // ---- block events: only for changes no hook saw ----

    /**
     * Some mods place or break blocks for a player later, from their own tick (building wands
     * that work over several ticks), where no actor is set. They still fire NeoForge's block
     * events with the player, so those are logged here. With an actor set the chunk hook already
     * logs the change, and nothing is logged twice.
     */
    private static void onBreak(BlockEvent.BreakEvent e) {
        if (Ctx.actor() != null || !(e.getPlayer() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel level)) return;
        BlockState before = e.getState();
        CompoundTag kept = null;
        BlockEntity be = level.getBlockEntity(e.getPos());
        if (be != null) {
            try {
                kept = be.saveWithFullMetadata(level.registryAccess());
            } catch (RuntimeException ignored) {
            }
        }
        String actor = p instanceof FakePlayer ? null : ((Owned) p).chronik$actor();
        if (actor == null) return;
        Track.block(level, e.getPos(), before, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), kept, actor, null);
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (Ctx.actor() != null || !(e.getEntity() instanceof ServerPlayer p) || p instanceof FakePlayer
                || !(e.getLevel() instanceof ServerLevel level)) return;
        Track.block(level, e.getPos(), e.getBlockSnapshot().getState(), e.getPlacedBlock(), null, ((Owned) p).chronik$actor(), null);
    }

    // ---- inspector ----

    private static void onLeftClick(PlayerInteractEvent.LeftClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Inspector.on(p)) return;
        e.setCanceled(true);
        if (e.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
        inspect(p, e.getPos(), false);
    }

    private static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Inspector.on(p) || e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        e.setCanceled(true);
        boolean container = p.level().getCapability(Capabilities.ItemHandler.BLOCK, e.getPos(), null) != null;
        BlockPos at = container || e.getFace() == null ? e.getPos() : e.getPos().relative(e.getFace());
        inspect(p, at, container);
    }

    private static void inspect(ServerPlayer p, BlockPos pos, boolean container) {
        if (Chronik.db() == null) return;
        Filter f = new Filter();
        f.world = Track.world(p.level());
        f.x = pos.getX();
        f.y = pos.getY();
        f.z = pos.getZ();
        f.radius = 0;
        if (container) {
            f.actions.add(Entry.TAKE);
            f.actions.add(Entry.PUT);
        } else {
            f.actions.add(Entry.BREAK);
            f.actions.add(Entry.PLACE);
            f.actions.add(Entry.CHANGE);
        }
        ChronikCommand.run(p.createCommandSourceStack(), f);
    }
}
