package de.kronwerke.chronik.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import de.kronwerke.chronik.Chronik;
import de.kronwerke.chronik.track.Actors;
import de.kronwerke.chronik.track.Owned;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Who placed a block entity. Machines act for this player whenever they tick, see
 * {@link Owned}. The owner is read from the saved attachment once and then kept in a field,
 * because this is asked for every ticking block entity every tick.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin implements Owned {

    @Unique
    private String chronik$owner;
    @Unique
    private boolean chronik$loaded;

    @Override
    public String chronik$actor() {
        return chronik$owner();
    }

    @Override
    public String chronik$cause() {
        return Actors.type(((BlockEntity) (Object) this).getType());
    }

    @Override
    public String chronik$owner() {
        if (!chronik$loaded) {
            String o = ((BlockEntity) (Object) this).getExistingData(Chronik.OWNER).orElse("");
            chronik$owner = o.isEmpty() ? null : o;
            chronik$loaded = true;
        }
        return chronik$owner;
    }

    @Override
    public void chronik$setOwner(String owner) {
        BlockEntity self = (BlockEntity) (Object) this;
        self.setData(Chronik.OWNER, owner);
        chronik$owner = owner;
        chronik$loaded = true;
        self.setChanged();
    }
}
