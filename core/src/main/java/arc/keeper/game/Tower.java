package arc.keeper.game;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;

import arc.keeper.Constants;

import java.util.Random;

/**
 * Procedurally generates the climbing tower above the player and recycles platforms
 * that have scrolled out of view below the death line.
 *
 * <p>The generator works "row by row" upward and uses an explicit difficulty integer
 * derived from the player's max height so the rule for what spawns is easy to reason
 * about and tune. Reachability is enforced by clamping horizontal deltas to a value
 * the maximum-charge jump can actually cross.
 */
public class Tower {

    /** Live platforms (sorted by ascending y is convenient but not strictly required). */
    public final Array<Platform> platforms = new Array<>();

    /** Y of the next row to attempt to generate. */
    private float nextSpawnY;

    /** State for the row-by-row decision. */
    private float lastPlatformCenterX;
    private float lastPlatformY;
    private final Random rng;

    /** Seeded random helpers (libGDX MathUtils.random does NOT accept a Random arg). */
    private float frand(float min, float max) { return min + rng.nextFloat() * (max - min); }
    private float frand(float max) { return rng.nextFloat() * max; }
    private float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** When true, the next platform we spawn will be the first of a teleport pair. */
    private boolean pendingTeleportPair;
    private Platform pendingTeleportFirst;

    /** Seeded once per run — supports daily challenges that share the seed. */
    public Tower(long seed) {
        rng = new Random(seed);
        // Initial floor.
        Platform floor = new Platform(PlatformType.STABLE,
            Constants.TOWER_LEFT + 30f, 40f,
            Constants.TOWER_WIDTH - 60f, 14f);
        platforms.add(floor);
        lastPlatformCenterX = floor.x + floor.w * 0.5f;
        lastPlatformY = floor.y;
        nextSpawnY = floor.y + 110f;
    }

    /** Ensures platforms exist up to the given y. Called every frame from the screen. */
    public void generateUpTo(float topY) {
        while (nextSpawnY < topY + 200f) {
            spawnRow(nextSpawnY);
        }
    }

    /** Drops any platforms whose top edge is below the given y. */
    public void cullBelow(float y) {
        for (int i = platforms.size - 1; i >= 0; i--) {
            Platform p = platforms.get(i);
            if (p.y + p.h < y - 100f) platforms.removeIndex(i);
        }
    }

    /** Per-frame update: lets MOVING_H, ROTATING, LASER etc. animate. */
    public void update(float dt) {
        for (int i = 0; i < platforms.size; i++) platforms.get(i).update(dt);
        // Sweep dead platforms — keeps memory bounded over a long run.
        for (int i = platforms.size - 1; i >= 0; i--) {
            if (platforms.get(i).dead) platforms.removeIndex(i);
        }
    }

    /**
     * Decides how hard the tower should feel at the given height. Returns a difficulty
     * tier from 0 (calm intro) upward. The numbers are intentionally tuned so each tier
     * unlocks the next mechanic without crowding out the older ones.
     */
    public static int difficultyFor(float height) {
        if (height < 350f)   return 0;
        if (height < 750f)   return 1;
        if (height < 1300f)  return 2;
        if (height < 2000f)  return 3;
        if (height < 2800f)  return 4;
        if (height < 3700f)  return 5;
        if (height < 4800f)  return 6;
        if (height < 6000f)  return 7;
        if (height < 7500f)  return 8;
        return 9;
    }

    private void spawnRow(float y) {
        int diff = difficultyFor(y);
        // Vertical spacing tightens-then-loosens as difficulty rises (gap variance grows).
        float gapMin = 80f + diff * 1.5f;
        float gapMax = 120f + diff * 8f;
        float gap = frand(gapMin, gapMax);

        // Horizontal reachability budget. Charged jump at 1080 impulse reaches roughly
        // 200 horizontal at 80 vertical — leave headroom so even mistimed jumps are recoverable.
        float maxDX = 175f;
        float dxRange = Math.min(maxDX, 70f + diff * 14f);
        float targetX = clamp(
            lastPlatformCenterX + frand(-dxRange, dxRange),
            Constants.TOWER_LEFT + Constants.WALL_THICKNESS + 22f,
            Constants.TOWER_RIGHT - Constants.WALL_THICKNESS - 22f);

        float w = frand(56f - diff * 2f, 110f - diff * 3f);
        if (w < 38f) w = 38f;
        float h = 12f;
        float x = targetX - w * 0.5f;
        // Clamp to playable column.
        x = clamp(x,
            Constants.TOWER_LEFT + Constants.WALL_THICKNESS + 4f,
            Constants.TOWER_RIGHT - Constants.WALL_THICKNESS - w - 4f);

        PlatformType type = pickType(diff, y, rng);
        Platform p = new Platform(type, x, y, w, h);
        configurePlatformByType(p, diff);

        // Teleport pairing: when we marked the previous one as the "first", attach.
        if (type == PlatformType.TELEPORT) {
            if (pendingTeleportFirst == null) {
                pendingTeleportFirst = p;
                pendingTeleportPair = true;
            } else {
                p.pair = pendingTeleportFirst;
                pendingTeleportFirst.pair = p;
                pendingTeleportFirst = null;
                pendingTeleportPair = false;
            }
        }

        platforms.add(p);
        lastPlatformCenterX = x + w * 0.5f;
        lastPlatformY = y;
        nextSpawnY += gap;

        // Sprinkle: rare extra hazard alongside the platform once difficulty is meaningful.
        maybeSpawnHazard(diff, y, x, w);

        // Rare "special events" (chase laser, narrow gap) seeded by height milestones.
        maybeSpawnSpecialEvent(diff, y);
    }

    /**
     * Type roulette. Each difficulty tier opens new options; weighting keeps STABLE common
     * so the tower remains readable.
     */
    private PlatformType pickType(int diff, float y, Random rng) {
        // If we owe a teleport pair, force the next platform to be its partner.
        if (pendingTeleportPair) return PlatformType.TELEPORT;

        // Build a weighted bucket.
        int wStable = 100;
        int wMovingH = diff >= 1 ? 18 + diff * 3 : 0;
        int wMovingV = diff >= 2 ? 8 + diff * 2 : 0;
        int wBounce  = diff >= 1 ? 10 : 0;
        int wDisappear = diff >= 3 ? 12 + diff * 2 : 0;
        int wIce     = diff >= 3 ? 10 + diff : 0;
        int wCrumble = diff >= 5 ? 12 + diff : 0;
        int wMagnetic= diff >= 5 ? 6 : 0;
        int wRotate  = diff >= 7 ? 10 : 0;
        int wGravFlip= diff >= 7 ? 6 : 0;
        int wTeleport= diff >= 8 ? 8 : 0;
        int wFake    = diff >= 8 ? 6 : 0;

        int total = wStable + wMovingH + wMovingV + wBounce + wDisappear +
                    wIce + wCrumble + wMagnetic + wRotate + wGravFlip + wTeleport + wFake;
        int r = rng.nextInt(total);
        int acc = 0;
        if ((acc += wStable)    > r) return PlatformType.STABLE;
        if ((acc += wMovingH)   > r) return PlatformType.MOVING_H;
        if ((acc += wMovingV)   > r) return PlatformType.MOVING_V;
        if ((acc += wBounce)    > r) return PlatformType.BOUNCE;
        if ((acc += wDisappear) > r) return PlatformType.DISAPPEARING;
        if ((acc += wIce)       > r) return PlatformType.ICE;
        if ((acc += wCrumble)   > r) return PlatformType.CRUMBLING;
        if ((acc += wMagnetic)  > r) return PlatformType.MAGNETIC;
        if ((acc += wRotate)    > r) return PlatformType.ROTATING;
        if ((acc += wGravFlip)  > r) return PlatformType.GRAVITY_FLIP;
        if ((acc += wTeleport)  > r) return PlatformType.TELEPORT;
        return PlatformType.FAKE;
    }

    private void configurePlatformByType(Platform p, int diff) {
        switch (p.type) {
            case MOVING_H:
                p.ampX = Math.min(60f, 30f + diff * 4f);
                p.speed = 1.0f + diff * 0.10f;
                p.phase = frand(0f, MathUtils.PI2);
                // Move base so it stays inside the column.
                p.baseX = clamp(p.baseX,
                    Constants.TOWER_LEFT + Constants.WALL_THICKNESS + p.ampX + 4f,
                    Constants.TOWER_RIGHT - Constants.WALL_THICKNESS - p.w - p.ampX - 4f);
                break;
            case MOVING_V:
                p.ampY = 22f + diff * 2f;
                p.speed = 0.9f + diff * 0.08f;
                break;
            case ROTATING:
                p.angularVelocity = frand(-160f, 160f);
                p.w = Math.max(p.w, 70f); // long bars rotate more dramatically
                break;
            case MAGNETIC:
                p.radius = 110f + diff * 6f;
                break;
            case BOUNCE:
                p.w = Math.max(40f, p.w * 0.7f);   // bouncers are smaller so it's a target
                break;
            case CRUMBLING:
                // No extra config; crumble timer starts on touch.
                break;
            case DISAPPEARING:
                // Slightly larger so the player has a chance to land first.
                p.w = Math.min(p.w * 1.15f, 120f);
                break;
            case LASER:
                p.laserPeriod = frand(1.4f, 2.4f);
                p.laserDutyOn = frand(0.35f, 0.55f);
                p.laserOffset = frand(0f, p.laserPeriod);
                break;
            default: break;
        }
    }

    /**
     * Spawns SPIKE / LASER / decorative obstacles next to platforms.
     * Density grows with difficulty but is gated by reachability rules.
     */
    private void maybeSpawnHazard(int diff, float y, float platX, float platW) {
        if (diff < 4) return;
        if (rng.nextFloat() > 0.10f + diff * 0.025f) return;
        // 50/50 spike vs laser at higher tiers.
        if (diff >= 6 && rng.nextFloat() < 0.40f) {
            // Laser emitter — small block on the wall at this row.
            float ex = (rng.nextBoolean() ? Constants.TOWER_LEFT : Constants.TOWER_RIGHT - 16f) ;
            Platform laser = new Platform(PlatformType.LASER, ex, y + 30f, 16f, 16f);
            laser.laserPeriod = frand(1.4f, 2.4f);
            laser.laserDutyOn = frand(0.30f, 0.50f);
            laser.laserOffset = frand(0f, laser.laserPeriod);
            platforms.add(laser);
        } else {
            // Spike strip — short row of spikes glued to wall or above platform.
            float sw = frand(28f, 60f);
            float sx;
            if (rng.nextBoolean()) {
                sx = Constants.TOWER_LEFT + Constants.WALL_THICKNESS + 2f;
            } else {
                sx = Constants.TOWER_RIGHT - Constants.WALL_THICKNESS - 2f - sw;
            }
            Platform spike = new Platform(PlatformType.SPIKE, sx, y + 60f, sw, 14f);
            platforms.add(spike);
        }
    }

    /**
     * Hooks for the rare "tower events" — a horizontal narrow gap, a stack of
     * disappearing platforms, etc. Triggered as the player crosses height milestones.
     */
    private void maybeSpawnSpecialEvent(int diff, float y) {
        if (diff < 4) return;
        if (rng.nextFloat() > 0.022f) return;
        int event = rng.nextInt(4);
        switch (event) {
            case 0: // Narrow precision gap — two wall-stub platforms with tiny gap.
            {
                float gapWidth = frand(60f, 90f);
                float gapCenter = frand(
                    Constants.TOWER_LEFT + Constants.WALL_THICKNESS + 40f + gapWidth * 0.5f,
                    Constants.TOWER_RIGHT - Constants.WALL_THICKNESS - 40f - gapWidth * 0.5f);
                float yy = y + 80f;
                float leftW  = (gapCenter - gapWidth * 0.5f) - (Constants.TOWER_LEFT + Constants.WALL_THICKNESS);
                float rightX = gapCenter + gapWidth * 0.5f;
                float rightW = (Constants.TOWER_RIGHT - Constants.WALL_THICKNESS) - rightX;
                if (leftW > 20f) platforms.add(new Platform(PlatformType.STABLE,
                    Constants.TOWER_LEFT + Constants.WALL_THICKNESS, yy, leftW, 14f));
                if (rightW > 20f) platforms.add(new Platform(PlatformType.STABLE,
                    rightX, yy, rightW, 14f));
                // Spikes on the ceiling above gap (a hazard you must thread).
                Platform spike = new Platform(PlatformType.SPIKE,
                    gapCenter - gapWidth * 0.5f + 4f, yy + 60f, gapWidth - 8f, 12f);
                platforms.add(spike);
                break;
            }
            case 1: // Stack of three disappearing — fast climb required.
            {
                for (int i = 0; i < 3; i++) {
                    float yy = y + 50f + i * 65f;
                    float x = frand(
                        Constants.TOWER_LEFT + Constants.WALL_THICKNESS + 30f,
                        Constants.TOWER_RIGHT - Constants.WALL_THICKNESS - 90f);
                    Platform d = new Platform(PlatformType.DISAPPEARING, x, yy, 75f, 12f);
                    platforms.add(d);
                }
                break;
            }
            case 2: // Pulsing laser corridor — three lasers stacked, offset phases.
            {
                if (diff >= 6) {
                    for (int i = 0; i < 3; i++) {
                        float yy = y + 50f + i * 60f;
                        Platform laser = new Platform(PlatformType.LASER,
                            Constants.TOWER_LEFT, yy, 16f, 12f);
                        laser.laserPeriod = 1.8f;
                        laser.laserDutyOn = 0.42f;
                        laser.laserOffset = i * 0.6f;
                        platforms.add(laser);
                    }
                }
                break;
            }
            case 3: // Bounce-pad combo — a single bounce pad with a high platform above.
            {
                float xx = frand(
                    Constants.TOWER_LEFT + Constants.WALL_THICKNESS + 30f,
                    Constants.TOWER_RIGHT - Constants.WALL_THICKNESS - 70f);
                platforms.add(new Platform(PlatformType.BOUNCE, xx, y + 40f, 50f, 12f));
                platforms.add(new Platform(PlatformType.STABLE, xx - 20f, y + 280f, 90f, 12f));
                break;
            }
        }
    }
}
