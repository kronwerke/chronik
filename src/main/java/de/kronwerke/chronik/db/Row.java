package de.kronwerke.chronik.db;

/** One logged action or message, as a lookup returns it. */
public record Row(long id, boolean message, long time, int action, String world, int x, int y, int z,
                  String actor, String actorLabel, String cause, String a, String b, int amount, byte[] data, boolean rolled, String text) {
}
