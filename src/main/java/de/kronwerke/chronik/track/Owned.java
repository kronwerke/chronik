package de.kronwerke.chronik.track;

/**
 * Added to entities and block entities: who is behind them. An owner is the player (by UUID)
 * who placed a machine or threw, lit or assembled something; it is saved with the thing, so a
 * drill that breaks blocks a week later is still put down to whoever built it.
 */
public interface Owned {

    /** The actor key used while this thing acts: its owner, a player's own UUID, or "#" and the type. */
    String chronik$actor();

    /** What it acts through when it acts for an owner (its type), or null. */
    String chronik$cause();

    /** The saved owner, or null. */
    String chronik$owner();

    void chronik$setOwner(String owner);
}
