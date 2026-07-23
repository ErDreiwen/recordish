#version 330

// Record-able - LCD Moiré post-processing shader
// Simulates RGB subpixel grid, sine-wave interference pattern, and slight ghosting.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform LcdMoireConfig {
    float Intensity;
};

out vec4 fragColor;

void main() {
    float t = Intensity;
    vec3 color = texture(InSampler, texCoord).rgb;

    // ── 1. Slight dimming ──
    color *= (1.0 - 0.06 * t);

    // ── 2. RGB subpixel grid (vertical R/G/B stripes every 3 pixels) ──
    float subStr = t * 0.4;
    if (subStr > 0.02) {
        float dimFactor = 1.0 - subStr * 0.5;
        int col = int(mod(gl_FragCoord.x, 3.0));
        if (col == 0) {
            // Red subpixel - dim G and B
            color.g *= dimFactor;
            color.b *= dimFactor;
        } else if (col == 1) {
            // Green subpixel - dim R and B
            color.r *= dimFactor;
            color.b *= dimFactor;
        } else {
            // Blue subpixel - dim R and G
            color.r *= dimFactor;
            color.g *= dimFactor;
        }
    }

    // ── 3. Moiré interference pattern (two overlapping sine frequencies) ──
    float moireStr = t * 0.12;
    if (moireStr > 0.01) {
        float freq1 = 0.08;  // horizontal frequency
        float freq2 = 0.12;  // vertical frequency
        float wave1 = sin(gl_FragCoord.x * freq1);
        float wave2 = sin(gl_FragCoord.y * freq2);
        float interference = wave1 * wave2;
        color += interference * moireStr * 0.16;
    }

    // ── 4. Horizontal scan line hint (LCD row structure) ──
    float rowDim = t * 0.06;
    if (mod(gl_FragCoord.y, 3.0) < 1.0) {
        color *= (1.0 - rowDim);
    }

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
