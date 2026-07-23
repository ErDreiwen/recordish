#version 330

// Record-able - VHS camcorder post-processing shader
// Simulates scan lines, chromatic aberration, color bleeding, noise, warm color temperature.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform VhsConfig {
    float Intensity;
};

out vec4 fragColor;

// ─── Pseudo-random hash (deterministic per-pixel noise) ───
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec2 uv = texCoord;
    float t = Intensity;

    // ── 1. Chromatic aberration (shift R left, B right) ──
    float caOffset = t * 2.5 / InSize.x;
    float r = texture(InSampler, vec2(uv.x - caOffset, uv.y)).r;
    float g = texture(InSampler, uv).g;
    float b = texture(InSampler, vec2(uv.x + caOffset, uv.y)).b;
    vec3 color = vec3(r, g, b);

    // ── 2. Color bleeding (horizontal smear via directional blur) ──
    float bleedStr = t * 0.3;
    if (bleedStr > 0.02) {
        vec2 bleedOff = vec2(2.0 / InSize.x, 0.0);
        float prevR = texture(InSampler, uv - bleedOff).r;
        float prevB = texture(InSampler, uv + bleedOff).b;
        color.r = mix(color.r, prevR, bleedStr);
        color.b = mix(color.b, prevB, bleedStr);
    }

    // ── 3. VHS color temperature: warm + slight desaturation ──
    float warmth = t * 0.15;
    float coolReduce = t * 0.10;
    float desat = t * 0.20;
    float gray = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(color, vec3(gray), desat);
    color.r = clamp(color.r + warmth * 0.16, 0.0, 1.0);
    color.b = clamp(color.b - coolReduce * 0.12, 0.0, 1.0);

    // ── 4. Random noise / grain (spatial hash) ──
    float noiseAmt = t * 0.08;
    if (noiseAmt > 0.005) {
        float pixelNoise = hash(gl_FragCoord.xy) * 2.0 - 1.0;
        color += pixelNoise * noiseAmt;
    }

    // ── 5. Scan lines (darken every other row) ──
    float scanFreq = t > 0.5 ? 2.0 : 3.0;
    float scanDark = 0.15 + 0.25 * t;
    float scanLine = mod(gl_FragCoord.y, scanFreq);
    if (scanLine < 1.0) {
        color *= (1.0 - scanDark);
    }

    // ── 6. Tracking distortion hint (subtle horizontal band) ──
    // Uses spatial position for a slow-moving appearance
    float bandPos = mod(gl_FragCoord.y * 0.01 + InSize.y * 0.3, InSize.y);
    float bandDist = abs(gl_FragCoord.y - bandPos);
    if (bandDist < 3.0 && t > 0.3) {
        float bandStr = (1.0 - bandDist / 3.0) * t * 0.06;
        color += bandStr;
    }

    // ── 7. Subtle chromatic fringe at edges ──
    float fringeStr = t * 0.04;
    if (uv.x < 0.01) {
        color.r += fringeStr;
    }
    if (uv.x > 0.99) {
        color.b += fringeStr;
    }

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
