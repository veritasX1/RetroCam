package com.retrocam.app.camera

/**
 * GLSL ES 1.00 (GLES 2.0) shaders for the single-pass "look" filter applied
 * to the live camera feed (and, via the same CameraX SurfaceProcessor, to
 * live preview and recorded video, via LookRenderer - see
 * CameraViewModel.tryBind for how this shader now reaches captured
 * photos and video too, applied once afterward instead of live).
 *
 * The fragment shader intentionally does everything in one pass rather
 * than a multi-pass pipeline: warmth/tint -> saturation -> contrast ->
 * highlight rolloff -> shadow lift -> grain -> softness blur -> vignette.
 * That keeps it cheap enough for real-time video on a mid-range phone.
 */
object ShaderSource {

    const val VERTEX = """
        uniform mat4 uTexMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
    """

    // sTexture is declared separately per variant (samplerExternalOES for
    // the live camera OES stream vs a plain sampler2D for the offscreen
    // photo bake pass - see PhotoLookBaker) since GLSL ES 1.00 doesn't let
    // one uniform declaration serve both types, but texture2D() itself
    // calls identically either way, so the rest of the look math (this
    // body) is shared verbatim - keeping one source of truth for what the
    // look actually does, instead of two shaders that could drift apart.
    private const val FRAGMENT_BODY = """
        varying vec2 vTexCoord;
        uniform sampler2D sGrain;
        uniform sampler2D sLut;

        uniform float uWarmth;          // -1..1
        uniform float uSaturation;      // 0..2
        uniform float uContrast;        // 0.5..1.6
        uniform float uHighlightRolloff;// 0..1
        uniform float uShadowLift;      // 0..1
        uniform float uGrainIntensity;  // 0..1
        uniform float uGrainBlendMode;  // 0=Normal 1=Multiplizieren 2=Filmkorn 3=Leuchtend - see applyGrainBlend
        uniform float uGrainSize;       // grain particle size, in SOURCE image pixels per grain texel
        uniform float uSoftness;        // 0..1, blur radius in texel units below
        uniform float uVignette;        // 0..1
        uniform vec2 uTexelSize;        // 1/width, 1/height of the input
        uniform vec2 uGrainOffset;      // per-frame random offset, animates the grain
        uniform float uLutStrength;     // 0..1, 0 = LUT off entirely (sLut left unbound/unused)
        uniform float uLutSize;         // color depth per axis, e.g. 33.0 for a 33x33x33 cube
        uniform float uLutTilesPerRow;  // sLut is a square grid of uLutSize B-slices, e.g. 6.0 for 33 slices in a 6x6 grid

        vec3 applyWarmth(vec3 c, float w) {
            return c + vec3(w * 0.08, -abs(w) * 0.02, -w * 0.08);
        }

        vec3 applySaturation(vec3 c, float s) {
            float luma = dot(c, vec3(0.299, 0.587, 0.114));
            return mix(vec3(luma), c, s);
        }

        vec3 applyContrast(vec3 c, float k) {
            return (c - 0.5) * k + 0.5;
        }

        vec3 applyHighlightRolloff(vec3 c, float amount) {
            // Soft-knee compression above ~0.6, blended in by `amount` so
            // 0 = hard digital clip, 1 = gentle filmic rolloff.
            vec3 soft = 1.0 - exp(-c * (1.6 - amount));
            return mix(c, soft, amount);
        }

        vec3 applyShadowLift(vec3 c, float amount) {
            return c + amount * 0.12 * (1.0 - c);
        }

        float vignetteFactor(vec2 uv, float amount) {
            vec2 d = uv - 0.5;
            float dist = length(d) * 1.4;
            return 1.0 - amount * smoothstep(0.4, 1.1, dist);
        }

        // Real film grain isn't equally visible everywhere - it reads much
        // more strongly in shadows than in highlights, the same way
        // digital sensor noise does (weak signal, so the noise floor
        // dominates). n is the raw bipolar grain sample (-0.5..0.5, both
        // lighter and darker specks); amt already folds in intensity and
        // the shader's overall 0.4 strength scale.
        //   0 Normal:          the old flat/symmetric behavior (peaks in
        //                      midtones, fades equally toward both black
        //                      and white) - kept as a plain option.
        //   1 Multiplizieren:  proportional to the base color itself, so
        //                      grain reads *stronger* in brighter areas -
        //                      a deliberately different, more stylized
        //                      look, not the "realistic" one.
        //   2 Filmkorn:        the physically-motivated default - grain
        //                      strength scales with (1-luma), so shadows
        //                      get full-strength grain and it fades out
        //                      approaching white, matching real film/
        //                      real sensor noise behavior.
        //   3 Leuchtend:       only ever lightens (screen-style, using
        //                      just the positive half of n) - shadows can
        //                      light up with grain, highlights can't get
        //                      any brighter, for a more "glowing" stylized
        //                      look.
        vec3 applyGrainBlend(vec3 color, float n, float amt, float mode) {
            float luma = dot(color, vec3(0.299, 0.587, 0.114));
            if (mode < 0.5) {
                float visibility = clamp(1.0 - abs(luma - 0.5) * 1.3, 0.15, 1.0);
                return color + n * amt * visibility;
            } else if (mode < 1.5) {
                return color * (1.0 + n * amt * 2.0);
            } else if (mode < 2.5) {
                return color + n * amt * 2.0 * (1.0 - luma);
            } else {
                float p = max(n, 0.0);
                return 1.0 - (1.0 - color) * (1.0 - p * amt * 2.0);
            }
        }

        // Standard "2D-strip" 3D LUT sampling: a .cube-style 3D LUT (R/G/B
        // each 0..uLutSize-1) baked into a square grid of 2D B-axis slices
        // (uLutTilesPerRow x uLutTilesPerRow tiles, each uLutSize x
        // uLutSize texels, R across, G down) - see LutTexture.kt for how
        // sLut itself gets loaded (the PNG is pre-baked from a .cube file
        // offline, not converted on-device). This exact technique (not any
        // specific LUT's data) is a well-known, widely-published approach
        // for doing 3D color-cube lookups on hardware with no native 3D
        // texture sampling - trilinear across R/G directly via the
        // texture's own bilinear filtering, linear across B done by hand
        // between the two nearest slices.
        vec3 applyLut(vec3 color) {
            color = clamp(color, 0.0, 1.0);
            float blue = color.b * (uLutSize - 1.0);
            float blueFloor = floor(blue);
            float blueCeil = min(blueFloor + 1.0, uLutSize - 1.0);
            float blueMix = blue - blueFloor;

            vec2 tile0 = vec2(mod(blueFloor, uLutTilesPerRow), floor(blueFloor / uLutTilesPerRow));
            vec2 tile1 = vec2(mod(blueCeil, uLutTilesPerRow), floor(blueCeil / uLutTilesPerRow));

            float tileFrac = 1.0 / uLutTilesPerRow;
            float texelInTile = tileFrac / uLutSize;
            vec2 inTileUv = color.rg * (tileFrac - texelInTile) + texelInTile * 0.5;

            vec3 sample0 = texture2D(sLut, tile0 * tileFrac + inTileUv).rgb;
            vec3 sample1 = texture2D(sLut, tile1 * tileFrac + inTileUv).rgb;
            return mix(sample0, sample1, blueMix);
        }

        vec3 sampleSoft(vec2 uv, float radiusTexels) {
            vec2 r = uTexelSize * radiusTexels;
            vec3 sum = vec3(0.0);
            sum += texture2D(sTexture, uv).rgb * 0.28;
            sum += texture2D(sTexture, uv + vec2( r.x,  0.0)).rgb * 0.12;
            sum += texture2D(sTexture, uv + vec2(-r.x,  0.0)).rgb * 0.12;
            sum += texture2D(sTexture, uv + vec2( 0.0,  r.y)).rgb * 0.12;
            sum += texture2D(sTexture, uv + vec2( 0.0, -r.y)).rgb * 0.12;
            sum += texture2D(sTexture, uv + vec2( r.x,  r.y)).rgb * 0.06;
            sum += texture2D(sTexture, uv + vec2(-r.x,  r.y)).rgb * 0.06;
            sum += texture2D(sTexture, uv + vec2( r.x, -r.y)).rgb * 0.06;
            sum += texture2D(sTexture, uv + vec2(-r.x, -r.y)).rgb * 0.06;
            return sum;
        }

        void main() {
            vec3 color = texture2D(sTexture, vTexCoord).rgb;
            if (uSoftness > 0.001) {
                vec3 blurred = sampleSoft(vTexCoord, mix(0.0, 3.0, uSoftness));
                color = mix(color, blurred, uSoftness);
            }

            color = applyWarmth(color, uWarmth);
            color = applySaturation(color, uSaturation);
            color = applyContrast(color, uContrast);
            color = applyHighlightRolloff(color, uHighlightRolloff);
            color = applyShadowLift(color, uShadowLift);

            // Creative LUT grade, layered on top of the parametric look
            // above rather than replacing it - a recipe can use either,
            // both, or neither. Applied before grain/vignette so those
            // still read as sitting "on top of" the final image, same as
            // before.
            if (uLutStrength > 0.001) {
                color = mix(color, applyLut(color), uLutStrength);
            }

            if (uGrainIntensity > 0.001) {
                // Grain is tied to actual SOURCE pixels (not normalized 0..1
                // UV) so its apparent size stays fine and consistent
                // regardless of photo/video resolution, instead of one
                // 256x256 noise map being stretched across the whole frame
                // (which read as soft, blobby "background noise").
                vec2 pixelPos = vTexCoord / uTexelSize;
                vec2 grainUv = pixelPos / (max(uGrainSize, 0.3) * 256.0) + uGrainOffset;
                float n = texture2D(sGrain, grainUv).r - 0.5;
                color = applyGrainBlend(color, n, uGrainIntensity * 0.4, uGrainBlendMode);
            }

            color *= vignetteFactor(vTexCoord, uVignette);

            gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """

    /** Live camera preview/video pass - samples the camera's OES stream. */
    const val FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES sTexture;
    """ + FRAGMENT_BODY

    /** Offscreen full-resolution photo bake pass (PhotoLookBaker) - samples
     * a plain 2D texture uploaded from the captured JPEG instead of a live
     * OES stream, so ImageCapture can be bound independently of the
     * Preview/VideoCapture shared GL stream and reach the sensor's real
     * still-capture resolution. Same look math (FRAGMENT_BODY) either way. */
    const val FRAGMENT_PHOTO = """
        precision mediump float;
        uniform sampler2D sTexture;
    """ + FRAGMENT_BODY
}
