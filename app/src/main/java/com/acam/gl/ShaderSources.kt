package com.acam.gl

/**
 * The single GLSL pipeline shared by preview, photo capture and video
 * recording. One shader => WYSIWYG: the saved file is the viewfinder.
 *
 * GLSL ES 1.00 (an ES 2.0 context is used so that samplerExternalOES is
 * available on every device; see GlCameraView).
 */
object ShaderSources {

    /** Shared vertex shader. The quad is a full clip-space triangle strip. */
    const val VERTEX = """
attribute vec4 aPosition;
attribute vec2 aUV;
uniform mat4 uPosMatrix;
uniform mat4 uTexMatrix;
varying vec2 vUV;
void main() {
    gl_Position = uPosMatrix * aPosition;
    vUV = (uTexMatrix * vec4(aUV, 0.0, 1.0)).xy;
}
"""

    /**
     * Plain nearest-neighbor blit: offscreen FBO -> screen (letterboxed)
     * or FBO -> encoder surface (upscaled). NEAREST filtering is what makes
     * low target resolutions show REAL visible pixels.
     */
    const val BLIT_FRAGMENT = """
precision mediump float;
varying vec2 vUV;
uniform sampler2D uTex;
void main() {
    gl_FragColor = vec4(texture2D(uTex, vUV).rgb, 1.0);
}
"""

    /**
     * The effects pass: camera frame (external OES texture, already
     * cropped/rotated/mirrored through uTexMatrix) -> offscreen FBO of the
     * exact target size.
     *
     * Effect parameters arrive packed into vec4 uniforms (see EffectUniforms)
     * to stay well within the 64-vec4 fragment uniform minimum of ES 2 GPUs.
     * Multi-tap effects are gated behind uniform conditions, so disabled
     * effects cost nothing on mid-range GPUs.
     */
    const val EFFECTS_FRAGMENT = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

varying vec2 vUV;

uniform samplerExternalOES uTex;   // camera frame
uniform sampler2D uOverlay;        // date stamp bitmap (white on transparent)
uniform vec2 uSize;                // output size in pixels
uniform float uTime;               // seconds, animates grain/leaks/scanlines
uniform vec4 uStampColor;          // date stamp tint (rgb, a)
uniform vec4 uStampRect;           // stamp placement x,y,w,h in output uv

uniform vec4 uColor0;  // x=exposure y=contrast z=brightness w=saturation
uniform vec4 uColor1;  // x=temperature y=tint z=highlights w=shadows
uniform vec4 uColor2;  // x=blackPoint y=toneCurve z=bwAmount w=sepiaAmount
uniform vec4 uMixR;    // channel mixer, output-red row
uniform vec4 uMixG;    // channel mixer, output-green row
uniform vec4 uMixB;    // channel mixer, output-blue row
uniform vec4 uFilm0;   // x=grainAmount y=grainSize z=grainColor w=colorNoise
uniform vec4 uFilm1;   // x=bloom y=dust z=lightLeak w=leakType
uniform vec4 uFilm2;   // x=scanlines y=jpegBlocking z=colorDepth w=dither
uniform vec4 uLens0;   // x=sharpness y=vignette z=vignetteSize w=aberration
uniform vec4 uLens1;   // x=distortion y=frame z=dateStampOn w=unused

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

// Animated noise: the offset changes every frame.
float hashT(vec2 p) {
    vec2 q = p + vec2(fract(uTime * 7.13), fract(uTime * 3.71));
    return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453123);
}

void main() {
    vec2 uv = vUV;
    float w = uSize.x;
    float h = uSize.y;

    // ---------- lens distortion (barrel / pincushion) ----------
    float dK = uLens1.x;
    if (abs(dK) > 0.001) {
        vec2 c = uv - 0.5;
        float r2 = dot(c, c);
        uv = 0.5 + c * (1.0 + dK * r2);
    }

    // ---------- scanline tracking jitter (VHS line wobble) ----------
    float scanA = uFilm2.x;
    if (scanA > 0.001) {
        float line = floor(uv.y * h * 0.5);
        float jit = (hashT(vec2(line, 3.7)) - 0.5) * 0.012 * scanA;
        float band = exp(-pow((uv.y - fract(uTime * 0.11)) * 12.0, 2.0));
        uv.x += jit + band * (hashT(vec2(line, 9.1)) - 0.5) * 0.06 * scanA;
    }

    vec2 cc = uv - 0.5;
    float rd = length(cc) / 0.7071;   // 0 center, ~1 corners

    // ---------- chromatic aberration ----------
    float caA = uLens0.w;
    vec3 col;
    if (caA > 0.001) {
        vec2 off = cc * (caA * 0.02 * rd);
        col.r = texture2D(uTex, uv + off).r;
        col.g = texture2D(uTex, uv).g;
        col.b = texture2D(uTex, uv - off).b;
    } else {
        col = texture2D(uTex, uv).rgb;
    }

    // ---------- sharpness / softness (unsharp mask; negative = blur) ----------
    float shA = uLens0.x;
    if (abs(shA) > 0.005) {
        vec2 px = 1.0 / uSize;
        vec3 n = texture2D(uTex, uv + vec2(0.0, px.y)).rgb;
        vec3 s = texture2D(uTex, uv - vec2(0.0, px.y)).rgb;
        vec3 e = texture2D(uTex, uv + vec2(px.x, 0.0)).rgb;
        vec3 ww = texture2D(uTex, uv - vec2(px.x, 0.0)).rgb;
        vec3 avg = (n + s + e + ww) * 0.25;
        col += (col - avg) * shA * 1.6;
    }

    // ---------- bloom / halation (warm glow around highlights) ----------
    float blA = uFilm1.x;
    if (blA > 0.005) {
        vec2 px = 3.0 / uSize;
        vec3 b;
        b  = texture2D(uTex, uv + vec2(px.x, 0.0)).rgb;
        b += texture2D(uTex, uv - vec2(px.x, 0.0)).rgb;
        b += texture2D(uTex, uv + vec2(0.0, px.y)).rgb;
        b += texture2D(uTex, uv - vec2(0.0, px.y)).rgb;
        b += texture2D(uTex, uv + px).rgb;
        b += texture2D(uTex, uv - px).rgb;
        b += texture2D(uTex, uv + vec2(px.x, -px.y)).rgb;
        b += texture2D(uTex, uv + vec2(-px.x, px.y)).rgb;
        b *= 0.125;
        vec3 bright = max(b - 0.62, 0.0) / 0.38;
        col += bright * blA * vec3(1.0, 0.72, 0.5) * 0.55;
    }

    // ---------- JPEG-style block / chroma artifacts ----------
    float jpA = uFilm2.y;
    if (jpA > 0.005) {
        vec2 bp = 8.0 / uSize;
        vec2 uvL = (floor(uv / bp) + 0.5) * bp;
        vec2 mp = 16.0 / uSize;
        vec2 uvC = (floor(uv / mp) + 0.5) * mp;
        vec3 bl = texture2D(uTex, uvL).rgb;
        vec3 bc = texture2D(uTex, uvC).rgb;
        vec3 chroma = bc - vec3(luma(bc));
        col = mix(col, vec3(luma(bl)) + chroma, jpA);
        vec2 f = fract(uv / bp);
        float edge = 0.0;
        if (abs(f.x - 0.5) > 0.46 || abs(f.y - 0.5) > 0.46) edge = 1.0;
        col += (hashT(floor(uv / bp)) - 0.5) * 0.05 * jpA * edge;
    }

    // ---------- exposure / temperature / tint ----------
    col *= exp2(uColor0.x);
    col.r *= 1.0 + uColor1.x * 0.25;
    col.b *= 1.0 - uColor1.x * 0.25;
    col.g *= 1.0 - uColor1.y * 0.20;
    col.r *= 1.0 + uColor1.y * 0.08;
    col.b *= 1.0 + uColor1.y * 0.08;

    // ---------- saturation / contrast ----------
    float l0 = luma(col);
    col = mix(vec3(l0), col, 1.0 + uColor0.w);
    col = (col - 0.5) * (1.0 + uColor0.y) + 0.5;

    // ---------- highlights / shadows ----------
    float lum1 = luma(col);
    col += uColor1.z * smoothstep(0.45, 1.0, lum1) * 0.5;
    col += uColor1.w * (1.0 - smoothstep(0.0, 0.55, lum1)) * 0.5;

    // ---------- black point lift (faded blacks) + brightness ----------
    col = col * (1.0 - uColor2.x) + uColor2.x;
    col += uColor0.z * 0.5;

    // ---------- channel mixer ----------
    col = vec3(dot(uMixR.xyz, col), dot(uMixG.xyz, col), dot(uMixB.xyz, col));

    // ---------- filmic S tone curve ----------
    float tc = uColor2.y;
    if (tc > 0.001) {
        vec3 c3 = clamp(col, 0.0, 1.0);
        col = mix(col, c3 * c3 * (3.0 - 2.0 * c3), tc);
    }

    // ---------- black & white / sepia ----------
    float bw = uColor2.z;
    if (bw > 0.001) col = mix(col, vec3(luma(col)), bw);
    float sp = uColor2.w;
    if (sp > 0.001) {
        vec3 se = vec3(dot(col, vec3(0.393, 0.769, 0.189)),
                       dot(col, vec3(0.349, 0.686, 0.168)),
                       dot(col, vec3(0.272, 0.534, 0.131)));
        col = mix(col, se, sp);
    }

    // ---------- film grain (animated, size relative to frame height) ----------
    float gA = uFilm0.x;
    if (gA > 0.005) {
        float cellPx = max(1.0, uFilm0.y * h * 0.002);
        vec2 gp = floor(uv * uSize / cellPx);
        float g = hashT(gp) - 0.5;
        float gl = luma(col);
        col += g * gA * 0.35 * (1.0 - abs(gl - 0.5) * 1.2);
        if (uFilm0.z > 0.001) {
            col.r += (hashT(gp + vec2(17.0, 3.0)) - 0.5) * gA * 0.3 * uFilm0.z;
            col.b += (hashT(gp + vec2(43.0, 9.0)) - 0.5) * gA * 0.3 * uFilm0.z;
        }
    }

    // ---------- chunky color noise ----------
    float cn = uFilm0.w;
    if (cn > 0.005) {
        vec2 np = floor(uv * uSize / max(1.0, h * 0.004));
        col.r += (hashT(np + vec2(5.0, 1.0)) - 0.5) * cn * 0.25;
        col.g += (hashT(np + vec2(11.0, 2.0)) - 0.5) * cn * 0.22;
        col.b += (hashT(np + vec2(23.0, 4.0)) - 0.5) * cn * 0.25;
    }

    // ---------- dust & scratches ----------
    float du = uFilm1.y;
    if (du > 0.005) {
        vec2 dp = floor(uv * uSize / max(2.0, h * 0.006));
        float seed = floor(uTime * 1.4);
        float dd = hash(vec2(seed, 7.0) + dp * 0.37);
        if (dd > 0.9965) {
            float b = (dd - 0.9965) / 0.0035;
            col = mix(col, vec3(b), du * 0.8);
        }
        float sx = floor(uv.x * 160.0);
        float sc = hash(vec2(sx, floor(uTime * 2.0)));
        if (sc > 0.992) {
            float fade = hash(vec2(uv.y * 3.0, sx));
            if (fade > 0.35) col += vec3(0.5 * fade * du);
        }
    }

    // ---------- scanlines ----------
    if (scanA > 0.001) {
        float sl = 0.5 + 0.5 * sin(uv.y * h * 3.14159);
        col *= 1.0 - scanA * 0.35 * sl;
        col *= 1.0 - scanA * 0.10 * (0.5 + 0.5 * sin(uTime * 2.0 + uv.y * 20.0));
    }

    // ---------- light leaks ----------
    float ll = uFilm1.z;
    if (ll > 0.005) {
        float lt = uFilm1.w;
        float flick = 0.75 + 0.25 * sin(uTime * 1.7);
        if (lt < 0.5) {
            float m = smoothstep(0.30, 0.0, uv.y) * flick;
            col += m * ll * vec3(1.0, 0.62, 0.30) * 0.8;
        } else if (lt < 1.5) {
            float m = exp(-pow((uv.x - 0.12) * 9.0, 2.0)) * flick;
            col += m * ll * vec3(1.0, 0.55, 0.25) * 0.9;
        } else {
            float m = (0.35 + 0.65 * exp(-pow((uv.y - fract(uTime * 0.07)) * 10.0, 2.0))) * flick;
            vec3 washed = col * vec3(1.25, 0.72, 0.55) + vec3(0.18, 0.02, 0.0);
            col = mix(col, washed, m * ll);
        }
    }

    // ---------- vignette ----------
    float vg = uLens0.y;
    if (vg > 0.005) {
        float vr = rd / max(uLens0.z, 0.05);
        col *= 1.0 - vg * smoothstep(0.55, 1.15, vr);
    }

    // ---------- color depth reduction + ordered dithering ----------
    float levels = max(uFilm2.z, 2.0);
    float dt = uFilm2.w;
    if (levels < 254.5 || dt > 0.005) {
        vec2 ip = floor(uv * uSize);
        float bA = mod(2.0 * mod(ip.x, 2.0) + 3.0 * mod(ip.y, 2.0), 4.0);
        float bB = mod(2.0 * mod(floor(ip.x * 0.5), 2.0) + 3.0 * mod(floor(ip.y * 0.5), 2.0), 4.0);
        float bay = 4.0 * bA + bB;                       // 0..15 ordered pattern
        float dith = (bay + 0.5) / 16.0 - 0.5;
        float d = dith * dt * (1.0 / levels) * 8.0;
        col = floor(clamp(col, 0.0, 1.0) * levels + 0.5 + d) / max(levels - 1.0, 1.0);
    }

    col = clamp(col, 0.0, 1.0);

    // ---------- border / frame ----------
    float fr = uLens1.y;
    if (fr > 0.5 && fr < 1.5) {
        // Polaroid: white border, thicker at the bottom.
        float bw = 0.045;
        float bb = 0.155;
        float inX = step(bw, uv.x) * step(uv.x, 1.0 - bw);
        float inY = step(bw, uv.y) * step(uv.y, 1.0 - bb);
        col = mix(vec3(0.965, 0.960, 0.940), col, inX * inY);
    } else if (fr > 1.5) {
        // Film strip: dark bands with sprocket holes.
        float tb = 0.115;
        float band = step(uv.y, tb) + step(1.0 - tb, uv.y);
        float fx = fract(uv.x * 10.0);
        float fyT = step(tb * 0.25, uv.y) * step(uv.y, tb * 0.75);
        float fyB = step(1.0 - tb * 0.75, uv.y) * step(uv.y, 1.0 - tb * 0.25);
        float hole = step(0.28, fx) * step(fx, 0.72) * max(fyT, fyB);
        col = mix(col, vec3(0.07, 0.065, 0.06), band * (1.0 - hole));
    }

    // ---------- date stamp overlay ----------
    if (uLens1.z > 0.5) {
        vec2 suv = (uv - uStampRect.xy) / uStampRect.zw;
        if (suv.x >= 0.0 && suv.x <= 1.0 && suv.y >= 0.0 && suv.y <= 1.0) {
            float a = texture2D(uOverlay, suv).a * uStampColor.a;
            col = mix(col, uStampColor.rgb, a);
        }
    }

    gl_FragColor = vec4(col, 1.0);
}
"""

    /**
     * Identical effects pass for a plain GL_TEXTURE_2D input, used by the
     * full-resolution offline photo render (ImageCapture JPEG). Generated
     * from EFFECTS_FRAGMENT so the two can never drift apart: the shader
     * that renders the saved photo is, line for line, the shader the
     * viewfinder runs (WYSIWYG).
     */
    val EFFECTS_FRAGMENT_2D: String = EFFECTS_FRAGMENT
        .replace("#extension GL_OES_EGL_image_external : require", "")
        .replace("samplerExternalOES", "sampler2D")
}
