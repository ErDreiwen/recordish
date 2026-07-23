#version 330

// Record-able - CRT monitor post-processing shader
// Simulates barrel distortion, scan lines, phosphor glow, and vignette.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform CrtConfig {
    float Intensity;
};

out vec4 fragColor;

void main() {
    float t = Intensity;
    vec2 uv = texCoord;

    // ── 1. Barrel distortion (curved screen effect) ──
    float distAmount = t * 0.15;
    if (distAmount > 0.005) {
        vec2 centered = uv * 2.0 - 1.0; // map to [-1, 1]
        float r2 = dot(centered, centered);
        vec2 distorted = centered * (1.0 + distAmount * r2);
        uv = distorted * 0.5 + 0.5; // map back to [0, 1]

        // Clamp: outside bounds → black (curved edge)
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
            fragColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
    }

    vec3 color = texture(InSampler, uv).rgb;

    // ── 2. Phosphor glow (brighten based on neighbor brightness) ──
    float bloomStr = t * 0.15;
    if (bloomStr > 0.01) {
        vec2 texel = 1.0 / InSize;
        vec3 left  = texture(InSampler, uv - vec2(texel.x, 0.0)).rgb;
        vec3 right = texture(InSampler, uv + vec2(texel.x, 0.0)).rgb;
        vec3 avg = (left + right) * 0.5;
        float brightness = dot(avg, vec3(0.299, 0.587, 0.114));
        if (brightness > 0.5) {
            color += (avg - color) * bloomStr * brightness;
        }
    }

    // ── 3. Scan lines (darken alternating rows) ──
    float scanDark = 0.20 + 0.25 * t;
    float scanLine = mod(gl_FragCoord.y, 2.0);
    if (scanLine < 1.0) {
        color *= (1.0 - scanDark);
    }

    // ── 4. Vignette (darkened edges) ──
    float vigStr = 0.3 + 0.5 * t;
    vec2 vigCoord = uv * 2.0 - 1.0;
    float dist2 = dot(vigCoord, vigCoord);
    float vig = 1.0 - vigStr * dist2;
    color *= max(0.0, vig);

    // ── 5. Phosphor glow tint (warm highlight) ──
    float glowTint = t * 0.03;
    color += vec3(glowTint, glowTint * 0.9, glowTint * 0.75);

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
