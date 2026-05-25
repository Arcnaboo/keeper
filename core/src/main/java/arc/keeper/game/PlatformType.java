package arc.keeper.game;

/** Every flavor of platform / hazard the tower can spawn. */
public enum PlatformType {
    /** Boring, reliable rectangle. The bedrock of the tower. */
    STABLE,
    /** Visible only briefly after first touch, then fades out. */
    DISAPPEARING,
    /** Slides left/right between two world-x bounds. */
    MOVING_H,
    /** Bobs up/down between two world-y bounds. */
    MOVING_V,
    /** Spins around its center; collision uses an oriented bounding box. */
    ROTATING,
    /** Crumbles with cracks after landing, dropping the player after a short delay. */
    CRUMBLING,
    /** Bounces the player upward with a fixed strong impulse (no charge needed). */
    BOUNCE,
    /** Slippery — minimal ground friction, makes precise landings dangerous. */
    ICE,
    /** Pulls the player toward its center while within its radius. */
    MAGNETIC,
    /** Briefly multiplies the player's gravity sign by a smaller value (low-grav buff). */
    GRAVITY_FLIP,
    /** Paired teleporters — touching one drops you out at the partner. */
    TELEPORT,
    /** Looks like STABLE but is non-collidable — careful! Subtle visual tell. */
    FAKE,
    /** Hazard. Kills on touch. */
    SPIKE,
    /** Hazard. Rhythmic horizontal beam that pulses on / off. */
    LASER
}
