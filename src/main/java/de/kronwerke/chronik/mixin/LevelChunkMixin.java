package de.kronwerke.chronik.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import de.kronwerke.chronik.track.Actors;
import de.kronwerke.chronik.track.Ctx;
import de.kronwerke.chronik.track.Owned;
import de.kronwerke.chronik.track.Track;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The one place every block change in a loaded world goes through. With nobody acting
 * ({@link Ctx}) it costs one field read. Otherwise it keeps the block entity that is about to go
 * (so a rollback can bring back a chest with its items), logs the change, and gives a block
 * entity that a player or their machine just placed its owner.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Shadow @Final Level level;

    @Shadow
    public abstract BlockState getBlockState(BlockPos pos);

    @Shadow
    public abstract BlockEntity getBlockEntity(BlockPos pos, LevelChunk.EntityCreationType type);

    @WrapMethod(method = "setBlockState")
    private BlockState chronik$log(BlockPos pos, BlockState state, boolean moved, Operation<BlockState> original) {
        String actor = Ctx.actor();
        if (actor == null || !(this.level instanceof ServerLevel)) return original.call(pos, state, moved);
        String cause = Ctx.cause();
        BlockState before = getBlockState(pos);
        CompoundTag kept = null;
        if (before.hasBlockEntity() && before.getBlock() != state.getBlock()) {
            BlockEntity old = getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
            if (old != null) {
                try {
                    kept = old.saveWithFullMetadata(this.level.registryAccess());
                } catch (RuntimeException ignored) {
                    // a mod's block entity that cannot save: log the block without it
                }
            }
        }
        BlockState result = original.call(pos, state, moved);
        if (result == null) return null;
        Track.block(this.level, pos, before, state, kept, actor, cause);
        if (state.hasBlockEntity() && Actors.isPlayer(actor)) {
            BlockEntity placed = getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
            if (placed instanceof Owned o && o.chronik$owner() == null) o.chronik$setOwner(actor);
        }
        return result;
    }
}
