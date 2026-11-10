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
            if (took > 0) item(p, Entry.PICKUP, orig.copyWithCount(took), e.getItemEntity().blockPosition());
        }
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
            message(p, Entry.COMMAND, "/" + e.getParseResults().getReader().getString());
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        Db db = Chronik.db();
        if (db != null) db.name(((Owned) p).chronik$actor(), p.getGameProfile().getName());
        if (ChronikConfig.LOG_SESSIONS.get()) message(p, Entry.JOIN, "");
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        Containers.left(p);
        Inspector.left(p);
        if (ChronikConfig.LOG_SESSIONS.get()) message(p, Entry.LEAVE, "");
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
