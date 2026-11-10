package de.kronwerke.chronik.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import de.kronwerke.chronik.track.Ctx;
import de.kronwerke.chronik.track.Owned;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * While a machine with an owner ticks, it acts for that owner: quarries, drills, block breakers
 * and placers, deployers and the like. Block entities without an owner (anything that was not
 * placed by a player, such as worldgen spawners or sculk catalysts) are not attributed.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class TickingBlockEntityMixin {

    @Shadow @Final private BlockEntity blockEntity;

    @WrapMethod(method = "tick")
    private void chronik$asOwner(Operation<Void> original) {
        String owner = ((Owned) this.blockEntity).chronik$owner();
        if (owner == null) {
            original.call();
            return;
        }
        String pa = Ctx.rawActor(), pc = Ctx.rawCause();
        Ctx.set(owner, ((Owned) this.blockEntity).chronik$cause());
        try {
            original.call();
        } finally {
            Ctx.set(pa, pc);
        }
    }
}
