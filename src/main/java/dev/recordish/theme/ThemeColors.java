package dev.recordish.theme;

/**
 * Color palette for a UI theme. All colors are in ARGB format (0xAARRGGBB).
 */
public final class ThemeColors {
    // ── Panel / Background ──
    public final int panelBackground;
    public final int panelBackgroundAlt;
    public final int panelBorder;

    // ── Accent / Interactive ──
    public final int accent;
    public final int accentHover;
    public final int accentDim;

    // ── Text ──
    public final int textPrimary;
    public final int textSecondary;
    public final int textMuted;
    public final int textError;
    public final int textSuccess;

    // ── Headers / Sections ──
    public final int headerText;
    public final int headerUnderline;
    public final int sectionBackground;
    public final int sectionHover;

    // ── Buttons ──
    public final int buttonBackground;
    public final int buttonBackgroundHover;
    public final int buttonBorder;
    public final int buttonText;

    // ── Scrollbar ──
    public final int scrollTrack;
    public final int scrollThumb;

    // ── Special Effects ──
    public final int scanlineColor;
    public final int grainColor;
    public final int glitchColor;
    public final int vignetteColor;

    public ThemeColors(Builder builder) {
        this.panelBackground = builder.panelBackground;
        this.panelBackgroundAlt = builder.panelBackgroundAlt;
        this.panelBorder = builder.panelBorder;
        this.accent = builder.accent;
        this.accentHover = builder.accentHover;
        this.accentDim = builder.accentDim;
        this.textPrimary = builder.textPrimary;
        this.textSecondary = builder.textSecondary;
        this.textMuted = builder.textMuted;
        this.textError = builder.textError;
        this.textSuccess = builder.textSuccess;
        this.headerText = builder.headerText;
        this.headerUnderline = builder.headerUnderline;
        this.sectionBackground = builder.sectionBackground;
        this.sectionHover = builder.sectionHover;
        this.buttonBackground = builder.buttonBackground;
        this.buttonBackgroundHover = builder.buttonBackgroundHover;
        this.buttonBorder = builder.buttonBorder;
        this.buttonText = builder.buttonText;
        this.scrollTrack = builder.scrollTrack;
        this.scrollThumb = builder.scrollThumb;
        this.scanlineColor = builder.scanlineColor;
        this.grainColor = builder.grainColor;
        this.glitchColor = builder.glitchColor;
        this.vignetteColor = builder.vignetteColor;
    }

    // ── Preset factories ──

    public static ThemeColors classic() {
        return new Builder()
                .panelBackground(0xD0101010).panelBackgroundAlt(0xD0181818).panelBorder(0xFF424242)
                .accent(0xFFFF4444).accentHover(0xFFFF6666).accentDim(0xFF992222)
                .textPrimary(0xFFFFFFFF).textSecondary(0xFFE0E0E0).textMuted(0xFFB8B8B8)
                .textError(0xFFFF7777).textSuccess(0xFF77FF77)
                .headerText(0xFFFFFFFF).headerUnderline(0xFFFF4444)
                .sectionBackground(0xFF1A1A1A).sectionHover(0xFF252525)
                .buttonBackground(0xFF2A2A2A).buttonBackgroundHover(0xFF3A3A3A).buttonBorder(0xFF555555).buttonText(0xFFE0E0E0)
                .scrollTrack(0xFF2A2A2A).scrollThumb(0xFFFF4444)
                .scanlineColor(0x00000000).grainColor(0x00000000).glitchColor(0x00000000).vignetteColor(0x00000000)
                .build();
    }

    public static ThemeColors vhs() {
        return new Builder()
                .panelBackground(0xE00A0A14).panelBackgroundAlt(0xE00E0E1C).panelBorder(0xFF1A3355)
                .accent(0xFFCC1E1E).accentHover(0xFFEE3333).accentDim(0xFF881515)
                .textPrimary(0xFFD4D4CC).textSecondary(0xFFAAAA99).textMuted(0xFF777766)
                .textError(0xFFFF5555).textSuccess(0xFF55FF88)
                .headerText(0xFFEEEEDD).headerUnderline(0xFFCC1E1E)
                .sectionBackground(0xFF0E0E1A).sectionHover(0xFF161628)
                .buttonBackground(0xFF14142A).buttonBackgroundHover(0xFF1E1E3A).buttonBorder(0xFF2A2A55).buttonText(0xFFCCCCBB)
                .scrollTrack(0xFF14142A).scrollThumb(0xFFCC1E1E)
                .scanlineColor(0x18000000).grainColor(0x0CFFFFFF).glitchColor(0x15FF0000).vignetteColor(0x40000000)
                .build();
    }

    public static ThemeColors cinema() {
        return new Builder()
                .panelBackground(0xE01A1008).panelBackgroundAlt(0xE0221810).panelBorder(0xFF4A3520)
                .accent(0xFFD4A846).accentHover(0xFFEEC060).accentDim(0xFF8A7030)
                .textPrimary(0xFFF5E6CC).textSecondary(0xFFCCBBA0).textMuted(0xFF998866)
                .textError(0xFFFF7755).textSuccess(0xFF88DD66)
                .headerText(0xFFF5E6CC).headerUnderline(0xFFD4A846)
                .sectionBackground(0xFF1E1408).sectionHover(0xFF2A1E10)
                .buttonBackground(0xFF221A10).buttonBackgroundHover(0xFF332818).buttonBorder(0xFF554422).buttonText(0xFFDDCCAA)
                .scrollTrack(0xFF221A10).scrollThumb(0xFFD4A846)
                .scanlineColor(0x00000000).grainColor(0x0AFFDDAA).glitchColor(0x00000000).vignetteColor(0x50000000)
                .build();
    }

    public static ThemeColors neon() {
        return new Builder()
                .panelBackground(0xE00A0018).panelBackgroundAlt(0xE0100020).panelBorder(0xFF2A1060)
                .accent(0xFFFF00FF).accentHover(0xFFFF44FF).accentDim(0xFFAA00AA)
                .textPrimary(0xFFEEEEFF).textSecondary(0xFFBBBBDD).textMuted(0xFF8888AA)
                .textError(0xFFFF4466).textSuccess(0xFF44FFAA)
                .headerText(0xFF00FFFF).headerUnderline(0xFFFF00FF)
                .sectionBackground(0xFF0E0020).sectionHover(0xFF180030)
                .buttonBackground(0xFF140030).buttonBackgroundHover(0xFF200044).buttonBorder(0xFF4400AA).buttonText(0xFFDDDDFF)
                .scrollTrack(0xFF140030).scrollThumb(0xFFFF00FF)
                .scanlineColor(0x10FF00FF).grainColor(0x08FFFFFF).glitchColor(0x1500FFFF).vignetteColor(0x30000033)
                .build();
    }

    public static ThemeColors minimal() {
        return new Builder()
                .panelBackground(0xE0141414).panelBackgroundAlt(0xE0181818).panelBorder(0xFF333333)
                .accent(0xFFDDDDDD).accentHover(0xFFFFFFFF).accentDim(0xFF999999)
                .textPrimary(0xFFEEEEEE).textSecondary(0xFFBBBBBB).textMuted(0xFF888888)
                .textError(0xFFFF6666).textSuccess(0xFF66FF66)
                .headerText(0xFFFFFFFF).headerUnderline(0xFF555555)
                .sectionBackground(0xFF1A1A1A).sectionHover(0xFF222222)
                .buttonBackground(0xFF252525).buttonBackgroundHover(0xFF333333).buttonBorder(0xFF444444).buttonText(0xFFDDDDDD)
                .scrollTrack(0xFF222222).scrollThumb(0xFF888888)
                .scanlineColor(0x00000000).grainColor(0x00000000).glitchColor(0x00000000).vignetteColor(0x00000000)
                .build();
    }

    public static ThemeColors forPreset(ThemePreset preset) {
        if (preset == null) {
            return classic();
        }
        switch (preset) {
            case VHS:
                return vhs();
            case CINEMA:
                return cinema();
            case NEON:
                return neon();
            case MINIMAL:
                return minimal();
            default:
                return classic();
        }
    }

    // ── Builder ──

    public static final class Builder {
        private int panelBackground = 0xD0101010;
        private int panelBackgroundAlt = 0xD0181818;
        private int panelBorder = 0xFF424242;
        private int accent = 0xFFFF4444;
        private int accentHover = 0xFFFF6666;
        private int accentDim = 0xFF992222;
        private int textPrimary = 0xFFFFFFFF;
        private int textSecondary = 0xFFE0E0E0;
        private int textMuted = 0xFFB8B8B8;
        private int textError = 0xFFFF7777;
        private int textSuccess = 0xFF77FF77;
        private int headerText = 0xFFFFFFFF;
        private int headerUnderline = 0xFFFF4444;
        private int sectionBackground = 0xFF1A1A1A;
        private int sectionHover = 0xFF252525;
        private int buttonBackground = 0xFF2A2A2A;
        private int buttonBackgroundHover = 0xFF3A3A3A;
        private int buttonBorder = 0xFF555555;
        private int buttonText = 0xFFE0E0E0;
        private int scrollTrack = 0xFF2A2A2A;
        private int scrollThumb = 0xFFFF4444;
        private int scanlineColor = 0x00000000;
        private int grainColor = 0x00000000;
        private int glitchColor = 0x00000000;
        private int vignetteColor = 0x00000000;

        public Builder panelBackground(int v) { this.panelBackground = v; return this; }
        public Builder panelBackgroundAlt(int v) { this.panelBackgroundAlt = v; return this; }
        public Builder panelBorder(int v) { this.panelBorder = v; return this; }
        public Builder accent(int v) { this.accent = v; return this; }
        public Builder accentHover(int v) { this.accentHover = v; return this; }
        public Builder accentDim(int v) { this.accentDim = v; return this; }
        public Builder textPrimary(int v) { this.textPrimary = v; return this; }
        public Builder textSecondary(int v) { this.textSecondary = v; return this; }
        public Builder textMuted(int v) { this.textMuted = v; return this; }
        public Builder textError(int v) { this.textError = v; return this; }
        public Builder textSuccess(int v) { this.textSuccess = v; return this; }
        public Builder headerText(int v) { this.headerText = v; return this; }
        public Builder headerUnderline(int v) { this.headerUnderline = v; return this; }
        public Builder sectionBackground(int v) { this.sectionBackground = v; return this; }
        public Builder sectionHover(int v) { this.sectionHover = v; return this; }
        public Builder buttonBackground(int v) { this.buttonBackground = v; return this; }
        public Builder buttonBackgroundHover(int v) { this.buttonBackgroundHover = v; return this; }
        public Builder buttonBorder(int v) { this.buttonBorder = v; return this; }
        public Builder buttonText(int v) { this.buttonText = v; return this; }
        public Builder scrollTrack(int v) { this.scrollTrack = v; return this; }
        public Builder scrollThumb(int v) { this.scrollThumb = v; return this; }
        public Builder scanlineColor(int v) { this.scanlineColor = v; return this; }
        public Builder grainColor(int v) { this.grainColor = v; return this; }
        public Builder glitchColor(int v) { this.glitchColor = v; return this; }
        public Builder vignetteColor(int v) { this.vignetteColor = v; return this; }
        public ThemeColors build() { return new ThemeColors(this); }
    }
}
