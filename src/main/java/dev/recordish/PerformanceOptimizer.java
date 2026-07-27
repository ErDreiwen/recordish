package dev.recordish;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Conservative performance recommendation and config-adjustment engine.
 *
 * <p>It never restarts or stops a recording. Applied changes affect the next
 * encoder session; callers may surface confirmation UI when requested.</p>
 */
public final class PerformanceOptimizer {
    public enum Action {
        NONE,
        FASTER_PRESET,
        LOWER_FPS,
        LOWER_RESOLUTION
    }

    public static final class Recommendation {
        public static final Recommendation NONE =
                new Recommendation(Action.NONE, "", false, "");

        private final Action action;
        private final String reason;
        private final boolean needsConfirmation;
        private final String proposedValue;

        public Recommendation(
                Action action,
                String reason,
                boolean needsConfirmation,
                String proposedValue) {
            this.action = action == null ? Action.NONE : action;
            this.reason = reason == null ? "" : reason;
            this.needsConfirmation = needsConfirmation;
            this.proposedValue = proposedValue == null
                    ? ""
                    : proposedValue;
        }

        public Action action() { return action; }
        public String reason() { return reason; }
        public boolean needsConfirmation() { return needsConfirmation; }
        public String proposedValue() { return proposedValue; }
        public Action getAction() { return action; }
        public String getReason() { return reason; }
        public boolean isNeedsConfirmation() { return needsConfirmation; }
        public String getProposedValue() { return proposedValue; }
        public boolean isNone() { return action == Action.NONE; }
    }

    private static final PerformanceOptimizer INSTANCE =
            new PerformanceOptimizer();
    private static final int LOW_PERFORMANCE_SAMPLES = 3;
    private static final long ACTION_COOLDOWN_MILLIS = 8000L;

    private int lowPerformanceStreak;
    private int tickCounter;
    private long lastActionAtMillis;
    private final List<Action> appliedThisSession =
            new ArrayList<Action>();
    private Recommendation pendingRecommendation = Recommendation.NONE;

    private PerformanceOptimizer() {
    }

    public static PerformanceOptimizer getInstance() {
        return INSTANCE;
    }

    /**
     * Once-per-tick entry point; evaluation itself occurs once per second.
     */
    public synchronized void onClientTick() {
        tickCounter++;
        if (tickCounter < 20) {
            return;
        }
        tickCounter = 0;

        if (!RecordingManager.getInstance().isRecording()) {
            lowPerformanceStreak = 0;
            pendingRecommendation = Recommendation.NONE;
            return;
        }

        RecordishConfig config = RecordishConfig.get();
        Recommendation recommendation = evaluate(
                Minecraft.getDebugFPS(),
                config,
                PerformanceMetrics.getInstance().snapshot());
        if (recommendation.isNone()) {
            return;
        }

        if (config.perfAutoAdjust
                && !recommendation.needsConfirmation()) {
            if (applyRecommendation(recommendation, config)) {
                RecordishMessages.send(
                        ChatCategory.WARNINGS,
                        "\u00a7ePerformance optimizer: "
                                + describe(recommendation.action())
                                + " (next recording).");
            }
        } else if (pendingRecommendation == recommendation) {
            RecordishMessages.send(
                    ChatCategory.WARNINGS,
                    "\u00a7ePerformance recommendation: "
                            + describe(recommendation.action())
                            + ". " + recommendation.reason());
        }
    }

    public synchronized void reset() {
        lowPerformanceStreak = 0;
        tickCounter = 0;
        lastActionAtMillis = 0L;
        appliedThisSession.clear();
        pendingRecommendation = Recommendation.NONE;
    }

    public synchronized List<Action> getAppliedActions() {
        return Collections.unmodifiableList(
                new ArrayList<Action>(appliedThisSession));
    }

    public synchronized Recommendation getPendingRecommendation() {
        return pendingRecommendation;
    }

    public synchronized void dismissPendingRecommendation() {
        pendingRecommendation = Recommendation.NONE;
        lowPerformanceStreak = 0;
    }

    public synchronized Recommendation evaluate(
            int currentGameFps,
            RecordishConfig config) {
        return evaluate(
                currentGameFps,
                config,
                PerformanceMetrics.getInstance().snapshot());
    }

    /**
     * Evaluates sustained game FPS, encoder queue health, and frame drops.
     */
    public synchronized Recommendation evaluate(
            int currentGameFps,
            RecordishConfig config,
            PerformanceMetrics.Snapshot metrics) {
        if (config == null || !config.perfOptimizerEnabled) {
            lowPerformanceStreak = 0;
            pendingRecommendation = Recommendation.NONE;
            return Recommendation.NONE;
        }

        int target = Math.max(10, config.perfMinFps);
        int safeFps = Math.max(0, currentGameFps);
        boolean lowGameFps = safeFps < target;
        boolean queuePressure = metrics != null
                && metrics.getQueueHealth()
                        != RecordingManager.QueueHealth.OK;
        boolean dropping = metrics != null
                && metrics.getDropFps() >= 0.5D;

        if (!lowGameFps && !queuePressure && !dropping) {
            lowPerformanceStreak = Math.max(
                    0,
                    lowPerformanceStreak - 1);
            if (lowPerformanceStreak == 0) {
                pendingRecommendation = Recommendation.NONE;
            }
            return Recommendation.NONE;
        }

        lowPerformanceStreak++;
        if (lowPerformanceStreak < LOW_PERFORMANCE_SAMPLES) {
            return Recommendation.NONE;
        }

        long now = System.currentTimeMillis();
        if (now - lastActionAtMillis < ACTION_COOLDOWN_MILLIS) {
            return Recommendation.NONE;
        }

        Action next = nextEnabledAction(config);
        if (next == Action.NONE) {
            pendingRecommendation = Recommendation.NONE;
            return Recommendation.NONE;
        }

        String reason = buildReason(
                safeFps,
                target,
                queuePressure,
                dropping,
                metrics);
        boolean confirmation = config.perfWarnBeforeAdjust
                || !config.perfAutoAdjust;
        String proposed = proposedValue(next, config);
        Recommendation recommendation = new Recommendation(
                next,
                reason,
                confirmation,
                proposed);

        boolean isNew = pendingRecommendation.isNone()
                || pendingRecommendation.action() != next
                || !pendingRecommendation.proposedValue().equals(proposed);
        pendingRecommendation = recommendation;
        return isNew ? recommendation : Recommendation.NONE;
    }

    /**
     * Applies one bounded configuration step. It deliberately does not touch
     * the current encoder/recording lifecycle.
     */
    public synchronized boolean applyRecommendation(
            Recommendation recommendation,
            RecordishConfig config) {
        if (recommendation == null
                || recommendation.isNone()
                || config == null
                || !config.perfOptimizerEnabled
                || !isEnabled(recommendation.action(), config)) {
            return false;
        }

        boolean changed;
        switch (recommendation.action()) {
            case FASTER_PRESET:
                changed = lowerQuality(config);
                break;
            case LOWER_FPS:
                changed = lowerFps(config);
                break;
            case LOWER_RESOLUTION:
                changed = lowerResolution(config);
                break;
            case NONE:
            default:
                changed = false;
                break;
        }
        if (!changed) {
            return false;
        }

        config.save();
        markApplied(
                recommendation.action(),
                System.currentTimeMillis());
        return true;
    }

    public synchronized boolean applyPendingRecommendation() {
        Recommendation pending = pendingRecommendation;
        return applyRecommendation(pending, RecordishConfig.get());
    }

    public synchronized void markApplied(Action action, long whenMillis) {
        if (action == null || action == Action.NONE) {
            return;
        }
        if (!appliedThisSession.contains(action)) {
            appliedThisSession.add(action);
        }
        lastActionAtMillis = Math.max(
                0L,
                whenMillis);
        lowPerformanceStreak = 0;
        pendingRecommendation = Recommendation.NONE;
    }

    private Action nextEnabledAction(RecordishConfig config) {
        Action[] order = config.perfModeGamePriority
                ? new Action[]{
                        Action.FASTER_PRESET,
                        Action.LOWER_FPS,
                        Action.LOWER_RESOLUTION}
                : new Action[]{
                        Action.FASTER_PRESET,
                        Action.LOWER_RESOLUTION,
                        Action.LOWER_FPS};
        for (Action action : order) {
            if (appliedThisSession.contains(action)
                    || !isEnabled(action, config)
                    || !canApply(action, config)) {
                continue;
            }
            return action;
        }
        return Action.NONE;
    }

    private static boolean isEnabled(
            Action action,
            RecordishConfig config) {
        switch (action) {
            case FASTER_PRESET:
                return config.perfActionFasterPreset;
            case LOWER_FPS:
                return config.perfActionLowerFps;
            case LOWER_RESOLUTION:
                return config.perfActionLowerRes;
            case NONE:
            default:
                return false;
        }
    }

    private static boolean canApply(
            Action action,
            RecordishConfig config) {
        switch (action) {
            case FASTER_PRESET:
                return !"performance".equals(config.quality);
            case LOWER_FPS:
                return config.fps > 30;
            case LOWER_RESOLUTION:
                return !"480p".equals(config.resolution);
            case NONE:
            default:
                return false;
        }
    }

    private static boolean lowerQuality(RecordishConfig config) {
        if ("high".equals(config.quality)) {
            config.quality = "balanced";
            return true;
        }
        if (!"performance".equals(config.quality)) {
            config.quality = "performance";
            return true;
        }
        return false;
    }

    private static boolean lowerFps(RecordishConfig config) {
        if (config.fps > 60) {
            config.fps = 60;
            return true;
        }
        if (config.fps > 30) {
            config.fps = 30;
            return true;
        }
        return false;
    }

    private static boolean lowerResolution(RecordishConfig config) {
        if ("native".equals(config.resolution)
                || "1080p".equals(config.resolution)) {
            config.resolution = "720p";
            return true;
        }
        if ("720p".equals(config.resolution)) {
            config.resolution = "480p";
            return true;
        }
        return false;
    }

    private static String proposedValue(
            Action action,
            RecordishConfig config) {
        switch (action) {
            case FASTER_PRESET:
                return "high".equals(config.quality)
                        ? "Balanced quality"
                        : "Performance quality";
            case LOWER_FPS:
                return config.fps > 60 ? "60 FPS" : "30 FPS";
            case LOWER_RESOLUTION:
                return "720p".equals(config.resolution)
                        ? "480p"
                        : "720p";
            case NONE:
            default:
                return "";
        }
    }

    private static String buildReason(
            int fps,
            int target,
            boolean queuePressure,
            boolean dropping,
            PerformanceMetrics.Snapshot metrics) {
        StringBuilder reason = new StringBuilder();
        if (fps < target) {
            reason.append("Game FPS ")
                    .append(fps)
                    .append(" is below target ")
                    .append(target);
        }
        if (queuePressure) {
            appendSeparator(reason);
            reason.append("encoder queue is ")
                    .append(metrics.getQueueHealth()
                            .name()
                            .toLowerCase());
        }
        if (dropping) {
            appendSeparator(reason);
            reason.append(String.format(
                    java.util.Locale.ROOT,
                    "dropping %.1f frame(s)/s",
                    metrics.getDropFps()));
        }
        return reason.toString();
    }

    private static void appendSeparator(StringBuilder builder) {
        if (builder.length() > 0) {
            builder.append("; ");
        }
    }

    public static String describe(Action action) {
        if (action == null) {
            return "No change";
        }
        switch (action) {
            case FASTER_PRESET:
                return "switch to a faster encoder preset";
            case LOWER_FPS:
                return "lower recording frame rate";
            case LOWER_RESOLUTION:
                return "lower recording resolution";
            case NONE:
            default:
                return "no change";
        }
    }
}
