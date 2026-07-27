package dev.recordish;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side automatic clip trigger state machine.
 *
 * <p>This class never assumes ownership of an existing recording. A forward
 * clip is stopped only when this manager successfully started that exact
 * recording session. Kill montages use the independent rolling replay buffer
 * and therefore do not alter {@link RecordingManager} state at all.</p>
 */
public final class AutoClipManager {
    private static final AutoClipManager INSTANCE = new AutoClipManager();
    private static final int TICKS_PER_SECOND = 20;
    private static final int KILL_TRACK_WINDOW_TICKS = 200;
    private static final int DAMAGE_CONFIRM_WINDOW_TICKS = 20;
    private static final int COMBAT_ACTION_GRACE_TICKS = 20;
    private static final int COMBAT_ACTION_IDLE_TICKS = 100000;
    private static final int PROJECTILE_TRACE_GRACE_TICKS = 2;
    private static final int PROJECTILE_ARM_GRACE_TICKS = 2;
    private static final int MONTAGE_SAVE_RETRY_TICKS = 30 * TICKS_PER_SECOND;
    private static final double SURVIVAL_ATTACK_REACH = 3.0D;
    private static final double EXTENDED_ATTACK_REACH = 6.0D;
    private static final long TRIGGER_COOLDOWN_MILLIS = 5000L;

    private boolean initialized;
    private boolean hasPlayerState;
    private boolean wasPlayerAlive = true;
    private int lastDimension;
    private World trackedWorld;

    private WeakReference<EntityLivingBase> killTarget;
    private WeakReference<EntityLivingBase> lastProcessedKill;
    private boolean killTargetIsPlayer;
    private int killTrackTicks = -1;
    private WeakReference<EntityLivingBase> damageCandidate;
    private int damageCandidateTicks = -1;
    private int damageCandidateBaselineHurtTime;
    private float damageCandidateBaselineHealth;
    private boolean damageCandidateFromProjectile;
    private int ticksSinceCombatAction = COMBAT_ACTION_IDLE_TICKS;
    private long attributionTick;
    private long lastOwnedProjectileInFlightTick = Long.MIN_VALUE;
    private final Map<Integer, ProjectileTrace> projectileTraces =
            new HashMap<Integer, ProjectileTrace>();
    private final Map<Integer, DamageSnapshot> damageSnapshots =
            new HashMap<Integer, DamageSnapshot>();

    private boolean ownsForwardRecording;
    private int forwardStopTicks = -1;
    private String forwardReason;
    private Path ownedOutputFile;

    private int montageSaveTicks = -1;
    private int montageWindowSeconds;
    private long montageWindowEndMillis;
    private String montagePrefix;
    private String montageReason;
    private int montageSaveRetryTicks;
    private long montageRetryAfterFrameSequence = -1L;

    private long lastTriggerMillis;

    private AutoClipManager() {
    }

    public static AutoClipManager getInstance() {
        return INSTANCE;
    }

    /**
     * Establishes death and dimension baselines without firing a trigger.
     */
    public void initialize() {
        if (initialized) {
            updatePlayerBaseline(Minecraft.getMinecraft());
            return;
        }
        initialized = true;
        clearTransientState(false);
        updatePlayerBaseline(Minecraft.getMinecraft());
        RecordishMod.LOGGER.info("AutoClipManager initialized.");
    }

    /**
     * Clears trigger tracking without stopping or otherwise touching a recording.
     *
     * <p>Call this on disconnect/world unload. Recording shutdown remains the
     * responsibility of the normal recording lifecycle.</p>
     */
    public void reset() {
        clearTransientState(true);
    }

    /**
     * Called once at the END of each client tick.
     */
    public void onClientTick() {
        if (!initialized) {
            initialize();
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        RecordingManager recordingManager = RecordingManager.getInstance();

        updateOwnedForwardClip(recordingManager);
        updatePendingMontage();

        if (minecraft == null
                || minecraft.thePlayer == null
                || minecraft.theWorld == null) {
            clearWorldTracking();
            return;
        }

        EntityPlayer player = minecraft.thePlayer;
        ensureWorldIdentity(minecraft.theWorld);
        RecordishConfig config = RecordishConfig.get();
        attributionTick++;
        clearRevivedProcessedKill(minecraft.theWorld);
        updateCombatActionTracker(minecraft, player);

        if (!hasPlayerState) {
            wasPlayerAlive = isAlive(player);
            lastDimension = player.dimension;
            hasPlayerState = true;
        }

        if (ownsForwardRecording) {
            // Keep trigger baselines current while the manager-owned clip runs,
            // otherwise its final tick could immediately retrigger a stale event.
            updatePlayerState(player);
            if (config.autoClipEnabled
                    && (config.autoClipOnKill
                        || config.autoClipOnPlayerKill)) {
                updateDamageCandidate();
                updateKillTracking(config);
                updateDamageSnapshots(minecraft);
            } else {
                clearCombatAttribution();
            }
            return;
        }

        if (!config.autoClipEnabled) {
            updatePlayerState(player);
            clearCombatAttribution();
            return;
        }

        if (config.autoClipOnDeath) {
            checkDeath(player, config);
        }
        if (config.autoClipOnDimensionChange) {
            checkDimension(player, config);
        }
        if (config.autoClipOnKill || config.autoClipOnPlayerKill) {
            updateDamageCandidate();
            scanForPlayerProjectileHits(minecraft, player);
            scanForRecentlyDamagedEntities(minecraft, player);
            checkTrackedKill(config);
        } else {
            clearCombatAttribution();
        }

        updatePlayerState(player);
    }

    /**
     * Records a living entity attacked by the local player. A confirmed death
     * within ten seconds can then fire a mob-kill or player-kill trigger.
     */
    public void onPlayerAttackEntity(Entity target) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null
                || minecraft.thePlayer == null
                || minecraft.theWorld == null
                || !(target instanceof EntityLivingBase)
                || target == minecraft.thePlayer) {
            return;
        }

        RecordishConfig config = RecordishConfig.get();
        if (!config.autoClipEnabled
                || (!config.autoClipOnKill && !config.autoClipOnPlayerKill)) {
            return;
        }
        ensureWorldIdentity(minecraft.theWorld);

        EntityLivingBase living = (EntityLivingBase) target;
        if (isConfirmedDead(living)) {
            return;
        }
        beginTracking(
                living,
                "direct local-player attack");
    }

    /**
     * Receives a Forge living-attack event as an attribution candidate.
     * Forge fires this event before its remote-world and invulnerability
     * checks, so the target is promoted to kill tracking only after a real
     * hurt, health-loss, or death transition is observed.
     */
    public void onLivingAttackCandidate(
            EntityLivingBase target,
            DamageSource source,
            float amount) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null
                || minecraft.thePlayer == null
                || minecraft.theWorld == null
                || target == null
                || target == minecraft.thePlayer
                || target.worldObj != minecraft.theWorld
                || source == null
                || amount <= 0.0F) {
            return;
        }

        RecordishConfig config = RecordishConfig.get();
        if (!config.autoClipEnabled
                || (!config.autoClipOnKill && !config.autoClipOnPlayerKill)) {
            return;
        }
        ensureWorldIdentity(minecraft.theWorld);

        Entity directAttacker = source.getEntity();
        Entity immediateSource = source.getSourceOfDamage();
        boolean ownerVerifiedProjectile =
                getProjectileOwner(immediateSource)
                        == minecraft.thePlayer
                || (source.isProjectile()
                    && directAttacker == minecraft.thePlayer);
        if (directAttacker != minecraft.thePlayer
                && !ownerVerifiedProjectile) {
            return;
        }

        EntityLivingBase tracked = killTarget == null
                ? null
                : killTarget.get();
        if (tracked == target) {
            return;
        }

        damageCandidate =
                new WeakReference<EntityLivingBase>(target);
        damageCandidateTicks = DAMAGE_CONFIRM_WINDOW_TICKS;
        damageCandidateBaselineHurtTime = target.hurtTime;
        damageCandidateBaselineHealth = target.getHealth();
        damageCandidateFromProjectile = ownerVerifiedProjectile;
    }

    /**
     * Central kill-attribution path shared by direct attacks, the guarded
     * recent-action fallback, and owner-verified projectiles.
     */
    private void beginTracking(
            EntityLivingBase living,
            String attribution) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null
                || minecraft.thePlayer == null
                || minecraft.theWorld == null
                || living == null
                || living == minecraft.thePlayer
                || living.worldObj != minecraft.theWorld
                || (living.isDead
                    && living.getHealth() > 0.0F
                    && living.deathTime <= 0)) {
            return;
        }

        EntityLivingBase processed = lastProcessedKill == null
                ? null
                : lastProcessedKill.get();
        if (processed == living) {
            if (isAlive(living)) {
                lastProcessedKill = null;
            } else {
                return;
            }
        }

        clearDamageCandidate();
        killTarget = new WeakReference<EntityLivingBase>(living);
        killTargetIsPlayer = living instanceof EntityPlayer;
        killTrackTicks = KILL_TRACK_WINDOW_TICKS;
        RecordishMod.LOGGER.debug(
                "[AutoClip] Tracking entity ({}): {}",
                attribution,
                safeEntityName(living));
    }

    private void updateDamageCandidate() {
        EntityLivingBase candidate = damageCandidate == null
                ? null
                : damageCandidate.get();
        if (candidate == null
                || trackedWorld == null
                || candidate.worldObj != trackedWorld
                || (candidate.isDead
                    && candidate.getHealth() > 0.0F
                    && candidate.deathTime <= 0)) {
            clearDamageCandidate();
            return;
        }

        boolean confirmed = isConfirmedDead(candidate)
                || candidate.getHealth()
                    < damageCandidateBaselineHealth - 0.001F
                || candidate.hurtTime
                    > damageCandidateBaselineHurtTime;
        if (confirmed) {
            boolean projectile = damageCandidateFromProjectile;
            clearDamageCandidate();
            beginTracking(
                    candidate,
                    projectile
                            ? "confirmed player-owned projectile damage"
                            : "confirmed local-player damage");
            return;
        }

        damageCandidateTicks--;
        if (damageCandidateTicks < 0) {
            clearDamageCandidate();
        }
    }

    private void clearDamageCandidate() {
        damageCandidate = null;
        damageCandidateTicks = -1;
        damageCandidateBaselineHurtTime = 0;
        damageCandidateBaselineHealth = 0.0F;
        damageCandidateFromProjectile = false;
    }

    /**
     * Tracks a recent local combat action. The fallback still requires a
     * precise view-ray hit on an entity that is currently hurt or dying, so a
     * swing cannot attribute arbitrary damage elsewhere in the world.
     */
    private void updateCombatActionTracker(
            Minecraft minecraft,
            EntityPlayer player) {
        boolean active = player.isSwingInProgress;
        try {
            if (minecraft.gameSettings != null) {
                active = active
                        || (minecraft.gameSettings.keyBindAttack != null
                            && minecraft.gameSettings.keyBindAttack
                                    .isKeyDown());
            }
        } catch (Throwable ignored) {
            // The vanilla arm-swing signal remains available.
        }

        if (active) {
            ticksSinceCombatAction = 0;
        } else if (ticksSinceCombatAction < COMBAT_ACTION_IDLE_TICKS) {
            ticksSinceCombatAction++;
        }
    }

    /**
     * Covers custom melee weapons that bypass Forge's attack event. Attribution
     * requires all of: a recent local combat action, active damage/death state,
     * a precise view-ray intersection, and no solid block before the target.
     */
    private void scanForRecentlyDamagedEntities(
            Minecraft minecraft,
            EntityPlayer player) {
        EntityLivingBase current = killTarget == null
                ? null
                : killTarget.get();
        if (current != null
                || ticksSinceCombatAction > COMBAT_ACTION_GRACE_TICKS) {
            return;
        }

        EntityLivingBase processed = lastProcessedKill == null
                ? null
                : lastProcessedKill.get();
        EntityLivingBase aimed = findAimedDamagedEntity(
                minecraft,
                player,
                processed);
        if (aimed != null) {
            beginTracking(
                    aimed,
                    "recent local combat action + aimed damage");
        }
    }

    private EntityLivingBase findAimedDamagedEntity(
            Minecraft minecraft,
            EntityPlayer player,
            EntityLivingBase processed) {
        try {
            Vec3 eye = player.getPositionEyes(1.0F);
            Vec3 look = player.getLook(1.0F);
            double reach = resolveAttackReach(minecraft);
            Vec3 end = eye.addVector(
                    look.xCoord * reach,
                    look.yCoord * reach,
                    look.zCoord * reach);
            AxisAlignedBB searchBox = player.getEntityBoundingBox()
                    .addCoord(
                            look.xCoord * reach,
                            look.yCoord * reach,
                            look.zCoord * reach)
                    .expand(1.0D, 1.0D, 1.0D);

            double obstructionDistanceSq =
                    reach * reach;
            MovingObjectPosition blockHit =
                    minecraft.theWorld.rayTraceBlocks(
                            eye,
                            end,
                            false,
                            true,
                            false);
            if (blockHit != null && blockHit.hitVec != null) {
                obstructionDistanceSq =
                        eye.squareDistanceTo(blockHit.hitVec);
            }

            EntityLivingBase best = null;
            double bestDistanceSq = Double.MAX_VALUE;
            List<Entity> candidates =
                    minecraft.theWorld
                            .getEntitiesWithinAABBExcludingEntity(
                                    player,
                                    searchBox);
            for (Entity entity : candidates) {
                if (!(entity instanceof EntityLivingBase)) {
                    continue;
                }
                EntityLivingBase living = (EntityLivingBase) entity;
                if (living == processed
                        || living == player
                        || (living.isDead
                            && living.getHealth() > 0.0F)
                        || !isHurtOrDying(living)) {
                    continue;
                }

                AxisAlignedBB bounds = living.getEntityBoundingBox();
                if (bounds == null) {
                    continue;
                }
                bounds = bounds.expand(0.3D, 0.3D, 0.3D);
                MovingObjectPosition intercept =
                        bounds.calculateIntercept(eye, end);
                double distanceSq;
                if (bounds.isVecInside(eye)) {
                    distanceSq = 0.0D;
                } else if (intercept != null
                        && intercept.hitVec != null) {
                    distanceSq =
                            eye.squareDistanceTo(intercept.hitVec);
                } else {
                    continue;
                }

                if (distanceSq
                            > obstructionDistanceSq + 0.0001D
                        || distanceSq >= bestDistanceSq) {
                    continue;
                }
                best = living;
                bestDistanceSq = distanceSq;
            }
            return best;
        } catch (Throwable t) {
            RecordishMod.LOGGER.debug(
                    "[AutoClip] Recent-damage aim scan failed.",
                    t);
            return null;
        }
    }

    private static double resolveAttackReach(
            Minecraft minecraft) {
        try {
            if (minecraft.playerController != null
                    && minecraft.playerController.extendedReach()) {
                return EXTENDED_ATTACK_REACH;
            }
        } catch (Throwable ignored) {
        }
        return SURVIVAL_ATTACK_REACH;
    }

    /**
     * Best-effort backup for ranged hits when a client-side Forge damage event
     * is unavailable. A victim must intersect the recent path of a projectile
     * explicitly owned by the local player and must currently be hurt/dying.
     */
    private void scanForPlayerProjectileHits(
            Minecraft minecraft,
            EntityPlayer player) {
        updateProjectileTraces(minecraft, player);

        try {
            EntityLivingBase current = killTarget == null
                    ? null
                    : killTarget.get();
            if (current != null) {
                return;
            }

            EntityLivingBase processed = lastProcessedKill == null
                    ? null
                    : lastProcessedKill.get();
            for (Entity candidate :
                    minecraft.theWorld.loadedEntityList) {
                if (!(candidate instanceof EntityLivingBase)) {
                    continue;
                }
                EntityLivingBase living =
                        (EntityLivingBase) candidate;
                if (living == player
                        || living == processed
                        || (living.isDead
                            && living.getHealth() > 0.0F)
                        || !hasNewDamageTransition(living)) {
                    continue;
                }

                for (ProjectileTrace trace :
                        projectileTraces.values()) {
                    if (trace.intersects(
                            living,
                            attributionTick,
                            minecraft.theWorld)) {
                        beginTracking(
                                living,
                                "recent player-owned projectile path + damage");
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            RecordishMod.LOGGER.debug(
                    "[AutoClip] Projectile attribution scan failed.",
                    t);
        } finally {
            updateDamageSnapshots(minecraft);
        }
    }

    private void updateProjectileTraces(
            Minecraft minecraft,
            EntityPlayer player) {
        try {
            for (Entity projectile :
                    minecraft.theWorld.loadedEntityList) {
                if (getProjectileOwner(projectile) != player) {
                    continue;
                }
                Integer entityId =
                        Integer.valueOf(projectile.getEntityId());
                ProjectileTrace trace =
                        projectileTraces.get(entityId);
                if (trace == null) {
                    trace = new ProjectileTrace();
                    projectileTraces.put(entityId, trace);
                }
                trace.update(projectile, attributionTick);
                if (isProjectileInFlight(projectile)) {
                    lastOwnedProjectileInFlightTick =
                            attributionTick;
                }
            }

            Iterator<Map.Entry<Integer, ProjectileTrace>> iterator =
                    projectileTraces.entrySet().iterator();
            while (iterator.hasNext()) {
                ProjectileTrace trace =
                        iterator.next().getValue();
                if (attributionTick - trace.lastSeenTick
                        > PROJECTILE_TRACE_GRACE_TICKS) {
                    iterator.remove();
                }
            }
        } catch (Throwable t) {
            RecordishMod.LOGGER.debug(
                    "[AutoClip] Projectile trace update failed.",
                    t);
        }
    }

    private static boolean isProjectileInFlight(
            Entity projectile) {
        double dx = projectile.posX
                - projectile.lastTickPosX;
        double dy = projectile.posY
                - projectile.lastTickPosY;
        double dz = projectile.posZ
                - projectile.lastTickPosZ;
        double displacementSq =
                dx * dx + dy * dy + dz * dz;
        if (displacementSq > 0.000001D) {
            return true;
        }
        if (projectile.ticksExisted > 1) {
            return false;
        }
        double motionSq =
                projectile.motionX * projectile.motionX
                + projectile.motionY * projectile.motionY
                + projectile.motionZ * projectile.motionZ;
        return motionSq > 0.000001D;
    }

    private boolean hasNewDamageTransition(
            EntityLivingBase living) {
        DamageSnapshot previous = damageSnapshots.get(
                Integer.valueOf(living.getEntityId()));
        if (previous == null
                || previous.entity.get() != living) {
            return isConfirmedDead(living);
        }
        return (!previous.dead && isConfirmedDead(living))
                || living.getHealth()
                    < previous.health - 0.001F
                || living.hurtTime > previous.hurtTime;
    }

    private void updateDamageSnapshots(
            Minecraft minecraft) {
        damageSnapshots.clear();
        if (minecraft == null
                || minecraft.theWorld == null) {
            return;
        }
        try {
            for (Entity entity :
                    minecraft.theWorld.loadedEntityList) {
                if (!(entity instanceof EntityLivingBase)) {
                    continue;
                }
                EntityLivingBase living =
                        (EntityLivingBase) entity;
                damageSnapshots.put(
                        Integer.valueOf(living.getEntityId()),
                        new DamageSnapshot(living));
            }
        } catch (Throwable t) {
            damageSnapshots.clear();
            RecordishMod.LOGGER.debug(
                    "[AutoClip] Damage snapshot update failed.",
                    t);
        }
    }

    private static Entity getProjectileOwner(Entity projectile) {
        if (projectile instanceof EntityArrow) {
            return ((EntityArrow) projectile).shootingEntity;
        }
        if (projectile instanceof EntityThrowable) {
            return ((EntityThrowable) projectile).getThrower();
        }
        if (projectile instanceof EntityFireball) {
            return ((EntityFireball) projectile).shootingEntity;
        }
        return null;
    }

    private static boolean isHurtOrDying(
            EntityLivingBase living) {
        return living.hurtTime > 0
                || living.deathTime > 0
                || living.getHealth() <= 0.0F;
    }

    private static final class ProjectileTrace {
        private double fromX;
        private double fromY;
        private double fromZ;
        private double toX;
        private double toY;
        private double toZ;
        private double motionX;
        private double motionY;
        private double motionZ;
        private long lastSeenTick;

        private void update(Entity projectile, long tick) {
            fromX = projectile.lastTickPosX;
            fromY = projectile.lastTickPosY;
            fromZ = projectile.lastTickPosZ;
            toX = projectile.posX;
            toY = projectile.posY;
            toZ = projectile.posZ;
            motionX = projectile.motionX;
            motionY = projectile.motionY;
            motionZ = projectile.motionZ;
            lastSeenTick = tick;
        }

        private boolean intersects(
                EntityLivingBase living,
                long currentTick,
                World world) {
            long age = Math.max(
                    0L,
                    currentTick - lastSeenTick);
            if (age > PROJECTILE_TRACE_GRACE_TICKS) {
                return false;
            }

            Vec3 from;
            Vec3 to;
            if (age == 0L) {
                from = new Vec3(fromX, fromY, fromZ);
                to = new Vec3(toX, toY, toZ);
            } else {
                double startScale = age - 1.0D;
                double endScale = age + 0.25D;
                from = new Vec3(
                        toX + motionX * startScale,
                        toY + motionY * startScale,
                        toZ + motionZ * startScale);
                to = new Vec3(
                        toX + motionX * endScale,
                        toY + motionY * endScale,
                        toZ + motionZ * endScale);
            }

            AxisAlignedBB bounds = living.getEntityBoundingBox();
            if (bounds == null) {
                return false;
            }
            bounds = bounds.expand(0.4D, 0.4D, 0.4D);
            MovingObjectPosition entityHit =
                    bounds.calculateIntercept(from, to);
            double entityDistanceSq;
            if (bounds.isVecInside(from)) {
                entityDistanceSq = 0.0D;
            } else if (entityHit != null
                    && entityHit.hitVec != null) {
                entityDistanceSq =
                        from.squareDistanceTo(entityHit.hitVec);
            } else if (bounds.isVecInside(to)) {
                entityDistanceSq =
                        from.squareDistanceTo(to);
            } else {
                return false;
            }

            if (world != null) {
                MovingObjectPosition blockHit =
                        world.rayTraceBlocks(
                                from,
                                to,
                                false,
                                true,
                                false);
                if (blockHit != null
                        && blockHit.hitVec != null
                        && from.squareDistanceTo(blockHit.hitVec)
                            + 0.0001D < entityDistanceSq) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class DamageSnapshot {
        private final WeakReference<EntityLivingBase> entity;
        private final float health;
        private final int hurtTime;
        private final boolean dead;

        private DamageSnapshot(EntityLivingBase living) {
            entity = new WeakReference<EntityLivingBase>(living);
            health = living.getHealth();
            hurtTime = living.hurtTime;
            dead = isConfirmedDead(living);
        }
    }

    /**
     * Called by the 1.8.9 achievement event bridge.
     */
    public void onAchievementEarned(String achievementTitle) {
        RecordishConfig config = RecordishConfig.get();
        if (!config.autoClipEnabled || !config.autoClipOnAchievement) {
            return;
        }
        triggerForwardClip(
                config,
                "Achievement: " + safeLabel(achievementTitle, "Achievement"));
    }

    /**
     * Called by the boss-death event bridge.
     */
    public void onBossKilled(String bossName) {
        RecordishConfig config = RecordishConfig.get();
        if (!config.autoClipEnabled || !config.autoClipOnBossKill) {
            return;
        }
        triggerForwardClip(
                config,
                "Boss Killed: " + safeLabel(bossName, "Boss"));
    }

    public boolean isAutoClipActive() {
        return ownsForwardRecording || montageSaveTicks >= 0;
    }

    /**
     * Whether kill-montage frame capture is currently needed. Tracking an
     * attributed combat target arms pre-roll capture; a confirmed kill keeps
     * it armed through the configured post-roll countdown.
     */
    public boolean isKillMontageCaptureArmed() {
        boolean projectileInFlight =
                lastOwnedProjectileInFlightTick
                    != Long.MIN_VALUE
                && attributionTick
                    - lastOwnedProjectileInFlightTick
                    <= PROJECTILE_ARM_GRACE_TICKS;
        return killTrackTicks >= 0
                || montageSaveTicks >= 0
                || (damageCandidateTicks >= 0
                    && damageCandidateFromProjectile)
                || projectileInFlight;
    }

    public int getRemainingSeconds() {
        if (ownsForwardRecording && forwardStopTicks >= 0) {
            return (forwardStopTicks + TICKS_PER_SECOND - 1)
                    / TICKS_PER_SECOND;
        }
        if (montageSaveTicks >= 0) {
            return (montageSaveTicks + TICKS_PER_SECOND - 1)
                    / TICKS_PER_SECOND;
        }
        return -1;
    }

    public int getAutoClipRemainingSeconds() {
        return getRemainingSeconds();
    }

    private void checkDeath(EntityPlayer player, RecordishConfig config) {
        boolean alive = isAlive(player);
        if (wasPlayerAlive && !alive) {
            triggerForwardClip(config, "Player Death");
        }
    }

    private void checkDimension(EntityPlayer player, RecordishConfig config) {
        int dimension = player.dimension;
        if (hasPlayerState && dimension != lastDimension) {
            triggerForwardClip(
                    config,
                    "Entered " + dimensionName(dimension));
            clearCombatAttribution();
        }
    }

    private void checkTrackedKill(RecordishConfig config) {
        EntityLivingBase tracked = killTarget == null ? null : killTarget.get();
        if (tracked == null) {
            clearKillTracking();
            return;
        }

        if (isConfirmedDead(tracked)) {
            lastProcessedKill =
                    new WeakReference<EntityLivingBase>(tracked);
            boolean playerKill = killTargetIsPlayer;
            String targetName = safeEntityName(tracked);
            clearKillTracking();

            if (playerKill) {
                if (config.autoClipOnPlayerKill) {
                    triggerKillClip(config, "Player Kill: " + targetName);
                }
            } else if (config.autoClipOnKill) {
                triggerKillClip(config, "Kill: " + targetName);
            }
            return;
        }

        if (tracked.isDead && tracked.getHealth() > 0.0F
                && tracked.deathTime <= 0) {
            // Removed, unloaded, or despawned while alive: never attribute this
            // to the local player as a kill.
            clearKillTracking();
            return;
        }

        killTrackTicks--;
        if (killTrackTicks < 0) {
            clearKillTracking();
        }
    }

    private void updateKillTracking(RecordishConfig config) {
        if (config.autoClipOnKill || config.autoClipOnPlayerKill) {
            checkTrackedKill(config);
        } else {
            clearKillTracking();
        }
    }

    private void triggerKillClip(RecordishConfig config, String reason) {
        if (config.autoClipKillMontage) {
            // A projectile or one-hit kill can be attributed before the first
            // armed render has initialized the replay buffer. Keep the
            // montage pending so render capture can warm it up.
            scheduleMontage(config, reason);
            return;
        }
        triggerForwardClip(config, reason);
    }

    private void scheduleMontage(RecordishConfig config, String reason) {
        if (montageSaveTicks >= 0) {
            RecordishMod.LOGGER.debug(
                    "[AutoClip] Montage already awaiting post-roll; skipped: {}",
                    reason);
            return;
        }
        if (!acquireCooldown(reason)) {
            return;
        }

        int postSeconds = Math.max(0, config.autoClipKillPostSeconds);
        montageSaveTicks = postSeconds * TICKS_PER_SECOND;
        montageWindowSeconds = Math.max(
                1,
                Math.max(0, config.autoClipKillPreSeconds)
                        + postSeconds);
        montageWindowEndMillis = System.nanoTime() / 1_000_000L
                + postSeconds * 1000L;
        montagePrefix = prefixFor(reason);
        montageReason = reason;
        montageSaveRetryTicks = MONTAGE_SAVE_RETRY_TICKS;
        montageRetryAfterFrameSequence = -1L;

        int availablePreRoll = ReplayBuffer.getInstance().getBufferedSeconds();
        int requestedPreRoll = Math.max(0, config.autoClipKillPreSeconds);
        RecordishMessages.send(
                ChatCategory.CLIPS,
                "\u00a7aKill montage triggered: \u00a7f" + reason
                        + " \u00a77(pre "
                        + Math.min(availablePreRoll, requestedPreRoll)
                        + "s, post " + postSeconds + "s)");
    }

    private void updatePendingMontage() {
        if (montageSaveTicks < 0) {
            return;
        }
        if (montageSaveTicks > 0) {
            montageSaveTicks--;
            return;
        }

        String prefix = montagePrefix == null
                ? "on-kill"
                : montagePrefix;
        String reason = montageReason == null
                ? "Kill montage"
                : montageReason;
        int windowSeconds = montageWindowSeconds;

        ReplayBuffer replayBuffer = ReplayBuffer.getInstance();
        if (montageSaveRetryTicks-- <= 0) {
            clearPendingMontage();
            RecordishMessages.send(
                    ChatCategory.CLIPS,
                    "\u00a7eKill montage could not be saved because the replay buffer did not become ready.");
            return;
        }
        if (!replayBuffer.isActive()) {
            // isKillMontageCaptureArmed() remains true, allowing the render
            // hook to initialize or recover the buffer before the deadline.
            return;
        }
        long sequence = replayBuffer.getSubmittedFrameSequence();
        if (replayBuffer.isSaving()) {
            if (montageRetryAfterFrameSequence < 0L) {
                montageRetryAfterFrameSequence = sequence;
            }
            return;
        }
        if (montageRetryAfterFrameSequence >= 0L
                && sequence <= montageRetryAfterFrameSequence) {
            // Ingestion pauses during another save. Require at least one
            // fresh post-save frame before retrying this montage.
            return;
        }

        ReplayBuffer.SaveResult result =
                replayBuffer.trySaveBuffer(
                        prefix,
                        windowSeconds,
                        montageWindowEndMillis);
        if (result == ReplayBuffer.SaveResult.ACCEPTED) {
            clearPendingMontage();
            RecordishMessages.send(
                    ChatCategory.CLIPS,
                    "\u00a7aKill montage save requested: \u00a7f" + reason);
            return;
        }
        if (result == ReplayBuffer.SaveResult.BUSY
                || result == ReplayBuffer.SaveResult.WARMING_UP) {
            montageRetryAfterFrameSequence =
                    replayBuffer.getSubmittedFrameSequence();
        }
    }

    private void clearPendingMontage() {
        montageSaveTicks = -1;
        montageWindowSeconds = 0;
        montageWindowEndMillis = 0L;
        montagePrefix = null;
        montageReason = null;
        montageSaveRetryTicks = 0;
        montageRetryAfterFrameSequence = -1L;
    }

    private void triggerForwardClip(RecordishConfig config, String reason) {
        if (!acquireCooldown(reason)) {
            return;
        }
        if (ownsForwardRecording) {
            return;
        }

        RecordingManager manager = RecordingManager.getInstance();
        if (manager.isActiveOrStopping()) {
            RecordishMod.LOGGER.info(
                    "[AutoClip] Event '{}' occurred while another recording was active; leaving it untouched.",
                    reason);
            RecordishMessages.send(
                    ChatCategory.CLIPS,
                    "\u00a7aClip-worthy moment: \u00a7f" + reason
                            + " \u00a77(already recording)");
            return;
        }

        manager.startRecording(prefixFor(reason));
        if (!manager.isRecording()) {
            RecordishMod.LOGGER.warn(
                    "[AutoClip] Could not start forward clip: {}",
                    reason);
            return;
        }

        ownsForwardRecording = true;
        ownedOutputFile = manager.getCurrentOutputFile();
        forwardReason = reason;
        forwardStopTicks = Math.max(
                1,
                Math.max(5, config.autoClipDuration)
                        * TICKS_PER_SECOND);
        RecordishMessages.send(
                ChatCategory.CLIPS,
                "\u00a7aAuto-clip started: \u00a7f" + reason
                        + " \u00a77(" + Math.max(5, config.autoClipDuration)
                        + "s)");
    }

    private void updateOwnedForwardClip(RecordingManager manager) {
        if (!ownsForwardRecording) {
            return;
        }

        if (!manager.isRecording() && !manager.isPaused()) {
            // The user or a safety guard already stopped this session. Relinquish
            // ownership immediately so a later manual recording cannot be
            // mistaken for this clip.
            clearForwardOwnership();
            return;
        }
        Path currentOutput = manager.getCurrentOutputFile();
        if (ownedOutputFile != null
                && !ownedOutputFile.equals(currentOutput)) {
            // A different recording replaced the manager-owned session before
            // this tick. Never apply the old countdown to the new recording.
            clearForwardOwnership();
            return;
        }

        if (forwardStopTicks > 0) {
            forwardStopTicks--;
        }
        if (forwardStopTicks > 0) {
            return;
        }

        String reason = forwardReason == null
                ? "Auto-clip"
                : forwardReason;
        clearForwardOwnership();
        RecordishMessages.send(
                ChatCategory.CLIPS,
                "\u00a7eAuto-clip complete: \u00a7f" + reason);
        manager.stopRecording(RecordingManager.StopReason.AUTO);
    }

    private boolean acquireCooldown(String reason) {
        long now = System.currentTimeMillis();
        if (lastTriggerMillis > 0L
                && now - lastTriggerMillis < TRIGGER_COOLDOWN_MILLIS) {
            RecordishMod.LOGGER.debug(
                    "[AutoClip] Trigger skipped during cooldown: {}",
                    reason);
            return false;
        }
        lastTriggerMillis = now;
        return true;
    }

    private void updatePlayerBaseline(Minecraft minecraft) {
        if (minecraft == null
                || minecraft.thePlayer == null
                || minecraft.theWorld == null) {
            hasPlayerState = false;
            trackedWorld = null;
            return;
        }
        trackedWorld = minecraft.theWorld;
        wasPlayerAlive = isAlive(minecraft.thePlayer);
        lastDimension = minecraft.thePlayer.dimension;
        hasPlayerState = true;
    }

    private void updatePlayerState(EntityPlayer player) {
        wasPlayerAlive = isAlive(player);
        lastDimension = player.dimension;
        hasPlayerState = true;
    }

    private void clearWorldTracking() {
        hasPlayerState = false;
        wasPlayerAlive = true;
        trackedWorld = null;
        clearCombatAttribution();
    }

    private void ensureWorldIdentity(World world) {
        if (trackedWorld == world) {
            return;
        }
        boolean replacingWorld =
                trackedWorld != null && world != null;
        trackedWorld = world;
        if (replacingWorld) {
            clearCombatAttribution();
        }
    }

    private void clearRevivedProcessedKill(World world) {
        EntityLivingBase processed = lastProcessedKill == null
                ? null
                : lastProcessedKill.get();
        if (processed == null
                || processed.worldObj != world
                || isAlive(processed)) {
            lastProcessedKill = null;
        }
    }

    private void clearCombatAttribution() {
        ticksSinceCombatAction = COMBAT_ACTION_IDLE_TICKS;
        lastOwnedProjectileInFlightTick = Long.MIN_VALUE;
        projectileTraces.clear();
        damageSnapshots.clear();
        clearDamageCandidate();
        clearKillTracking();
        lastProcessedKill = null;
    }

    private void clearKillTracking() {
        killTarget = null;
        killTargetIsPlayer = false;
        killTrackTicks = -1;
    }

    private void clearForwardOwnership() {
        ownsForwardRecording = false;
        forwardStopTicks = -1;
        forwardReason = null;
        ownedOutputFile = null;
    }

    private void clearTransientState(boolean clearInitialization) {
        if (clearInitialization) {
            initialized = false;
        }
        hasPlayerState = false;
        wasPlayerAlive = true;
        lastDimension = 0;
        trackedWorld = null;
        attributionTick = 0L;
        clearCombatAttribution();
        clearForwardOwnership();
        clearPendingMontage();
        lastTriggerMillis = 0L;
    }

    private static boolean isAlive(EntityLivingBase entity) {
        return entity != null
                && entity.isEntityAlive()
                && entity.getHealth() > 0.0F;
    }

    private static boolean isConfirmedDead(EntityLivingBase entity) {
        return entity != null
                && (entity.getHealth() <= 0.0F || entity.deathTime > 0);
    }

    private static String safeEntityName(EntityLivingBase entity) {
        if (entity == null) {
            return "Entity";
        }
        try {
            return safeLabel(entity.getName(), "Entity");
        } catch (Throwable ignored) {
            return "Entity";
        }
    }

    private static String safeLabel(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        String cleaned = value.trim()
                .replace('\n', ' ')
                .replace('\r', ' ');
        return cleaned.length() > 80
                ? cleaned.substring(0, 80)
                : cleaned;
    }

    private static String prefixFor(String reason) {
        String lower = reason == null
                ? ""
                : reason.trim().toLowerCase(Locale.ROOT);
        String base;
        if (lower.startsWith("player death")) {
            base = "on-death";
        } else if (lower.startsWith("achievement:")) {
            base = "on-achievement";
        } else if (lower.startsWith("boss killed:")) {
            base = "on-boss";
        } else if (lower.startsWith("player kill:")) {
            base = "on-player-kill";
        } else if (lower.startsWith("kill:")) {
            base = "on-kill";
        } else if (lower.startsWith("entered ")
                || lower.startsWith("dimension ")) {
            base = "on-dimension";
        } else {
            base = "auto-clip";
        }
        return base;
    }

    private static String dimensionName(int dimension) {
        if (dimension == -1) {
            return "The Nether";
        }
        if (dimension == 0) {
            return "Overworld";
        }
        if (dimension == 1) {
            return "The End";
        }
        return "Dimension " + dimension;
    }
}
