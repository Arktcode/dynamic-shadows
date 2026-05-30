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



// Main

void main() {
    vec2 texel = 1.0 / u_resolution;

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

    float distFade = smoothstep(0.0, 0.15, alpha);

    float edgeFactor = smoothstep(0.0, 0.40, alpha) * (1.0 - smoothstep(0.50, 1.0, alpha));

    float contactBoost = smoothstep(0.55, 1.0, alpha) * u_contactShadow;

    float sunE  = u_sunElevation;

    vec3 dayTint    = vec3(0.06, 0.07, 0.12);
    float horizonFactor = exp(-sunE * sunE * 8.0);
    vec3 horizonTint    = vec3(0.16, 0.06, 0.01) * horizonFactor;
    float nightFactor   = clamp(-sunE, 0.0, 1.0);
    vec3 nightTint      = vec3(0.03, 0.02, 0.09) * nightFactor;

    vec3 rawTint    = dayTint + horizonTint + nightTint;
    vec3 shadowColor = mix(vec3(0.0), rawTint, u_shadowTint);

    shadowColor += vec3(0.025, 0.010, 0.0) * edgeFactor * u_shadowTint * (1.0 - nightFactor);

    float finalAlpha = alpha * distFade * (1.0 + contactBoost);
    finalAlpha = clamp(finalAlpha, 0.0, 1.0);

    gl_FragColor = vec4(shadowColor, finalAlpha) * v_color;
}
