varying vec4 v_color;
varying vec2 v_texCoord;

uniform sampler2D u_texture;
uniform vec2      u_resolution;
uniform vec2      u_cameraPos;
uniform vec2      u_camSize;

uniform float u_radius;
uniform vec2  u_blurDir;
uniform float u_sunElevation;
uniform float u_time;
uniform float u_edgeNoise;
uniform float u_shadowTint;
uniform float u_contactShadow;

// ── Noise helpers ─────────────────────────────────────────────────────────────

float hash(vec2 p) {
    p = fract(p * vec2(127.1, 311.7));
    p += dot(p, p + 17.43);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Two-octave fBm for richer, natural edge irregularity
float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 2; i++) {
        v   += valueNoise(p) * amp;
        p   *= 2.1;
        amp *= 0.5;
    }
    return v;
}

// ── Main ──────────────────────────────────────────────────────────────────────

void main() {
    vec2 texel = 1.0 / u_resolution;

    // ── 1. Separable Gaussian blur (9-tap, one direction per pass) ───────────
    // When sun is near horizon (low elevation), shadows are longer and softer →
    // scale the blur radius up so penumbra widens at dawn/dusk.
    float elevFactor  = 1.0 - clamp(u_sunElevation, 0.0, 1.0); // 0 at noon, 1 at horizon
    float dynamicRad  = u_radius * (1.0 + elevFactor * 1.4);    // up to 2.4× at horizon

    // Gaussian weights for kernel [0, ±1r, ±2r, ±3r, ±4r]
    // Sum ≈ 1.0: 0.227 + 2×0.1945 + 2×0.1216 + 2×0.054 + 2×0.013
    vec2 step = u_blurDir * texel * dynamicRad;
    vec4 col  = texture2D(u_texture, v_texCoord)                     * 0.2270;
    col += texture2D(u_texture, v_texCoord + step        )           * 0.1945;
    col += texture2D(u_texture, v_texCoord - step        )           * 0.1945;
    col += texture2D(u_texture, v_texCoord + step * 2.0  )           * 0.1216;
    col += texture2D(u_texture, v_texCoord - step * 2.0  )           * 0.1216;
    col += texture2D(u_texture, v_texCoord + step * 3.0  )           * 0.054;
    col += texture2D(u_texture, v_texCoord - step * 3.0  )           * 0.054;
    col += texture2D(u_texture, v_texCoord + step * 4.0  )           * 0.013;
    col += texture2D(u_texture, v_texCoord - step * 4.0  )           * 0.013;

    float alpha = col.a;

    // ── 2. Distance-based fade — shadow dissipates at the tip ────────────────
    float distFade = smoothstep(0.0, 0.50, alpha);

    // ── 3. Procedural fBm edge noise ─────────────────────────────────────────
    vec2 worldPos = v_texCoord * u_camSize + (u_cameraPos - u_camSize * 0.5);
    vec2 noiseUV = worldPos * 0.016;
    float noise  = fbm(noiseUV);

    float edgeFactor = smoothstep(0.0, 0.40, alpha) * (1.0 - smoothstep(0.50, 1.0, alpha));
    float noiseDisp  = (noise - 0.5) * 2.0 * u_edgeNoise * edgeFactor;
    alpha = clamp(alpha + noiseDisp, 0.0, 1.0);



    // ── 5. Contact-shadow darkening (root zone is always deepest) ────────────
    // High-alpha areas (near caster base) get an extra multiplicative darkening.
    // This pushes the "grounded" zone darker without touching the tip.
    float contactBoost = smoothstep(0.55, 1.0, alpha) * u_contactShadow;

    // ── 6. Dynamic tint from solar elevation ─────────────────────────────────
    // sunElevation: -1=midnight, 0=horizon, +1=noon
    float sunE  = u_sunElevation;   // -1 .. +1

    // Day: soft blue-grey (sky scatter fills shadow colour)
    vec3 dayTint    = vec3(0.06, 0.07, 0.12);
    // Dawn / dusk: warm orange-red (low sun bakes the shadow in amber)
    // Peaks at elevation ≈ 0 (horizon crossing)
    float horizonFactor = exp(-sunE * sunE * 8.0);  // Gaussian peak at sunE=0
    vec3 horizonTint    = vec3(0.16, 0.06, 0.01) * horizonFactor;
    // Night: deep indigo-violet (moonlight tint)
    float nightFactor   = clamp(-sunE, 0.0, 1.0);
    vec3 nightTint      = vec3(0.03, 0.02, 0.09) * nightFactor;

    vec3 rawTint    = dayTint + horizonTint + nightTint;
    vec3 shadowColor = mix(vec3(0.0), rawTint, u_shadowTint);

    // Warm halo at the very edge (physics: diffracted warm light outlines shadow)
    shadowColor += vec3(0.025, 0.010, 0.0) * edgeFactor * u_shadowTint * (1.0 - nightFactor);

    // ── Combine ───────────────────────────────────────────────────────────────
    float finalAlpha = alpha * distFade * (1.0 + contactBoost);
    finalAlpha = clamp(finalAlpha, 0.0, 1.0);

    gl_FragColor = vec4(shadowColor, finalAlpha) * v_color;
}
