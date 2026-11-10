package de.kronwerke.chronik.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import de.kronwerke.chronik.track.Ctx;
import de.kronwerke.chronik.track.Inspector;
import de.kronwerke.chronik.track.Owned;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * While the server handles what a player does (use, break, interact, click), that player is
 * the one acting. These handlers first run on the network thread only to hand themselves to the
 * server thread; the actor is only set on the server thread.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PacketMixin {

    @Shadow public ServerPlayer player;

    @org.spongepowered.asm.mixin.Unique
    private void chronik$run(Runnable call) {
        if (!Ctx.onServerThread()) {
            call.run();
            return;
        }
        String pa = Ctx.rawActor(), pc = Ctx.rawCause();
        Ctx.set(((Owned) this.player).chronik$actor(), null);
        try {
            call.run();
        } finally {
            Ctx.set(pa, pc);
        }
    }

    @WrapMethod(method = "handleUseItemOn")
    private void chronik$useOn(ServerboundUseItemOnPacket packet, Operation<Void> original) {
        if (Ctx.onServerThread()) Inspector.lastUsed(this.player, packet.getHitResult().getBlockPos());
        chronik$run(() -> original.call(packet));
    }

    @WrapMethod(method = "handleUseItem")
    private void chronik$use(ServerboundUseItemPacket packet, Operation<Void> original) {
        chronik$run(() -> original.call(packet));
    }

    @WrapMethod(method = "handlePlayerAction")
    private void chronik$action(ServerboundPlayerActionPacket packet, Operation<Void> original) {
        chronik$run(() -> original.call(packet));
    }

    @WrapMethod(method = "handleInteract")
    private void chronik$interact(ServerboundInteractPacket packet, Operation<Void> original) {
        chronik$run(() -> original.call(packet));
    }

    @WrapMethod(method = "handleContainerClick")
    private void chronik$click(ServerboundContainerClickPacket packet, Operation<Void> original) {
        chronik$run(() -> original.call(packet));
    }
}
