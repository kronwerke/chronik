package de.kronwerke.chronik.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import de.kronwerke.chronik.track.Ctx;
import de.kronwerke.chronik.track.Owned;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** While an entity ticks, it is the one acting (or its owner for TNT, contraptions and such). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @WrapMethod(method = "tickNonPassenger")
    private void chronik$asEntity(Entity entity, Operation<Void> original) {
        String pa = Ctx.rawActor(), pc = Ctx.rawCause();
        Owned o = (Owned) entity;
        Ctx.set(o.chronik$actor(), o.chronik$cause());
        try {
            original.call(entity);
        } finally {
            Ctx.set(pa, pc);
        }
    }

    @WrapMethod(method = "tickPassenger")
    private void chronik$asPassenger(Entity vehicle, Entity passenger, Operation<Void> original) {
        String pa = Ctx.rawActor(), pc = Ctx.rawCause();
        Owned o = (Owned) passenger;
        Ctx.set(o.chronik$actor(), o.chronik$cause());
        try {
            original.call(vehicle, passenger);
        } finally {
            Ctx.set(pa, pc);
        }
    }
}
