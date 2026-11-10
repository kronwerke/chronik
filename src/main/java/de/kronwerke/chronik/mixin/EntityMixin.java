package de.kronwerke.chronik.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import de.kronwerke.chronik.Chronik;
import de.kronwerke.chronik.track.Actors;
import de.kronwerke.chronik.track.Owned;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Who an entity acts for, worked out once and kept, see {@link Owned}. */
@Mixin(Entity.class)
public abstract class EntityMixin implements Owned {

    @Unique
    private String chronik$actor;
    @Unique
    private String chronik$cause;
    @Unique
    private boolean chronik$known;

    @Unique
    private void chronik$resolve() {
        Entity self = (Entity) (Object) this;
        if (self instanceof Player) {
            chronik$actor = self.getUUID().toString();
            chronik$cause = null;
        } else {
            String owner = chronik$owner();
            String type = Actors.type(self.getType());
            if (owner != null) {
                chronik$actor = owner;
                chronik$cause = type;
            } else {
                chronik$actor = "#" + type;
                chronik$cause = null;
            }
        }
        chronik$known = true;
    }

    @Override
    public String chronik$actor() {
        if (!chronik$known) chronik$resolve();
        return chronik$actor;
    }

    @Override
    public String chronik$cause() {
        if (!chronik$known) chronik$resolve();
        return chronik$cause;
    }

    @Override
    public String chronik$owner() {
        String o = ((Entity) (Object) this).getExistingData(Chronik.OWNER).orElse("");
        return o.isEmpty() ? null : o;
    }

    @Override
    public void chronik$setOwner(String owner) {
        ((Entity) (Object) this).setData(Chronik.OWNER, owner);
        chronik$known = false;
    }
}
