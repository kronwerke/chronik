package de.kronwerke.chronik.track;

/**
 * Who is acting right now on the server thread.
 * <p>
 * Every block change goes through one place (LevelChunk.setBlockState), but that place does not
 * know who caused it. So the code paths that act for someone set the actor around themselves: a
 * player's packets (use, break, interact, click), every entity tick (the player, the owner of a
 * thrown or placed entity such as TNT or a Create contraption, or the mob type), every tick of a
 * block entity that has an owner (machines, deployers, drills, quarries) and every explosion (the
 * one who lit it). A change made while no actor is set is nature (water flowing, fire, growth)
 * and is not logged.
 * <p>
 * Only the server thread reads or writes this; other threads always see "nobody".
 */
public final class Ctx {

    private static volatile Thread serverThread;
    private static String actor;
    private static String cause;

    private Ctx() {
    }

    public static void serverThread(Thread t) {
        serverThread = t;
        actor = null;
        cause = null;
    }

    public static boolean onServerThread() {
        return Thread.currentThread() == serverThread;
    }

    /** The actor key of whoever acts now, or null. */
    public static String actor() {
        return Thread.currentThread() == serverThread ? actor : null;
    }

    /** What the actor acted through (a machine, TNT, a contraption), or null when directly. */
    public static String cause() {
        return Thread.currentThread() == serverThread ? cause : null;
    }

    /**
     * Sets the actor. Callers keep the previous values ({@link #rawActor}, {@link #rawCause}) in
     * locals and put them back in a finally block, so nested actors (an explosion inside a
     * player's click) unwind correctly and nothing is allocated per entity tick.
     */
    public static void set(String newActor, String newCause) {
        actor = newActor;
        cause = newCause;
    }

    /** The current actor without the thread check, only for saving and restoring around {@link #set}. */
    public static String rawActor() {
        return actor;
    }

    public static String rawCause() {
        return cause;
    }

    /** Safety net once per tick: nothing may stay set between ticks. */
    public static void clear() {
        actor = null;
        cause = null;
    }
}
