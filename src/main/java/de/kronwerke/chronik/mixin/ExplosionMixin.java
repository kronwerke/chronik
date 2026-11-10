package de.kronwerke.chronik.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import de.kronwerke.chronik.track.Actors;
import de.kronwerke.chronik.track.Ctx;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Explosion;

/**
 * An explosion acts for whoever lit it: the player behind TNT or a projectile, the owner of a
 * placed bomb, the creeper itself, or the player whose click set it off (beds, anchors).
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @Shadow
    public abstract LivingEntity getIndirectSourceEntity();

    @Shadow
    public abstract Entity getDirectSourceEntity();

    @WrapMethod(method = "finalizeExplosion")
    private void chronik$asIgniter(boolean particles, Operation<Void> original) {
        if (!Ctx.onServerThread()) {
            original.call(particles);
            return;
        }
        Entity direct = getDirectSourceEntity();
        LivingEntity indirect = getIndirectSourceEntity();
        String actor;
        String cause = direct != null ? Actors.type(direct.getType()) : "explosion";
        String directActor = Actors.of(direct);
        if (indirect != null && Actors.isPlayer(Actors.of(indirect))) actor = Actors.of(indirect);
        else if (Actors.isPlayer(directActor)) actor = directActor;
        else if (Ctx.rawActor() != null && Actors.isPlayer(Ctx.rawActor())) actor = Ctx.rawActor();
        else if (directActor != null) actor = directActor;
        else actor = "#explosion";
        if (actor.equals(directActor) && direct != null && !Actors.isPlayer(actor)) cause = null; // a creeper is the actor itself
        String pa = Ctx.rawActor(), pc = Ctx.rawCause();
        Ctx.set(actor, cause);
        try {
            original.call(particles);
        } finally {
            Ctx.set(pa, pc);
        }
    }
}
