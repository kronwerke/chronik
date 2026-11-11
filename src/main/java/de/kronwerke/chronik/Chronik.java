package de.kronwerke.chronik;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;

import de.kronwerke.chronik.cmd.ChronikCommand;
import de.kronwerke.chronik.db.Db;
import de.kronwerke.chronik.roll.Rollbacks;
import de.kronwerke.chronik.track.Ctx;
import de.kronwerke.chronik.track.Events;
import de.kronwerke.chronik.web.Api;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Chronik: who changed which block, container and entity, and when, on a modded server.
 * Server side only; clients need nothing.
 */
@Mod(Chronik.ID)
public final class Chronik {

    public static final String ID = "chronik";
    public static final Logger LOG = LogUtils.getLogger();

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ID);
    /** The owner (a player's UUID) of an entity or block entity, saved with it. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> OWNER = ATTACHMENTS.register("owner",
            () -> AttachmentType.builder(() -> "").serialize(Codec.STRING).build());

    private static Db db;
    private static MinecraftServer server;
    private static long nextPurge;

    public Chronik(IEventBus modBus, ModContainer container) {
        ATTACHMENTS.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, ChronikConfig.SPEC, "chronik.toml");
        NeoForge.EVENT_BUS.addListener(Chronik::onStarting);
        NeoForge.EVENT_BUS.addListener(Chronik::onStopped);
        NeoForge.EVENT_BUS.addListener(Chronik::onTick);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> ChronikCommand.register(e.getDispatcher()));
        Events.register();
    }

    /** The log, or null while the server is not running. */
    public static Db db() {
        return db;
    }

    public static MinecraftServer server() {
        return server;
    }

    private static void onStarting(ServerStartingEvent e) {
        server = e.getServer();
        Ctx.serverThread(Thread.currentThread());
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("chronik.db");
        Db d = new Db(file);
        try {
            d.open();
            db = d;
            LOG.info("Chronik logs to {}", file.toAbsolutePath().normalize());
        } catch (Exception ex) {
            LOG.error("Chronik could not open {}, nothing is logged", file, ex);
            db = null;
        }
        nextPurge = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        Api.start();
    }

    private static void onStopped(ServerStoppedEvent e) {
        Api.stop();
        Rollbacks.stopAll();
        de.kronwerke.chronik.track.Events.flushPickups(true);
        if (db != null) {
            db.close();
            LOG.info("Chronik wrote {} entries this run, dropped {}", db.written(), db.dropped());
        }
        db = null;
        server = null;
        Ctx.serverThread(null);
    }

    private static void onTick(ServerTickEvent.Pre e) {
        Ctx.clear();
        Rollbacks.tick();
        de.kronwerke.chronik.track.Events.flushPickups(false);
        long now = System.currentTimeMillis();
        if (db != null && now >= nextPurge) {
            nextPurge = now + TimeUnit.DAYS.toMillis(1);
            long cutoff = now - TimeUnit.DAYS.toMillis(ChronikConfig.RETENTION_DAYS.get());
            db.purge(cutoff).thenAccept(n -> {
                if (n > 0) LOG.info("Chronik deleted {} entries older than {} days", n, ChronikConfig.RETENTION_DAYS.get());
            });
        }
    }
}
