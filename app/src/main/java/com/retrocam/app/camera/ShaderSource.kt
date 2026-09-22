package com.retrocam.app.camera

/**
 * GLSL ES 1.00 (GLES 2.0) shaders for the single-pass "look" filter applied
 * to the live camera feed (and, via the same CameraX SurfaceProcessor, to
 * captured photos and recorded video - see RetroCamSurfaceProcessor).
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

    const val FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        uniform sampler2D sGrain;

        uniform float uWarmth;          // -1..1
        uniform float uSaturation;      // 0..2
        uniform float uContrast;        // 0.5..1.6
        uniform float uHighlightRolloff;// 0..1
        uniform float uShadowLift;      // 0..1
        uniform float uGrainIntensity;  // 0..1
        uniform float uGrainSize;       // grain particle size, in SOURCE image pixels per grain texel
        uniform float uSoftness;        // 0..1, blur radius in texel units below
        uniform float uVignette;        // 0..1
        uniform vec2 uTexelSize;        // 1/width, 1/height of the input
        uniform vec2 uGrainOffset;      // per-frame random offset, animates the grain

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

            if (uGrainIntensity > 0.001) {
                // Grain is tied to actual SOURCE pixels (not normalized 0..1
                // UV) so its apparent size stays fine and consistent
                // regardless of photo/video resolution, instead of one
                // 256x256 noise map being stretched across the whole frame
                // (which read as soft, blobby "background noise").
                vec2 pixelPos = vTexCoord / uTexelSize;
                vec2 grainUv = pixelPos / (max(uGrainSize, 0.3) * 256.0) + uGrainOffset;
                float n = texture2D(sGrain, grainUv).r - 0.5;
                float luma = dot(color, vec3(0.299, 0.587, 0.114));
                float visibility = 1.0 - abs(luma - 0.5) * 1.3; // most visible in midtones
                color += n * uGrainIntensity * 0.4 * clamp(visibility, 0.15, 1.0);
            }

            color *= vignetteFactor(vTexCoord, uVignette);

            gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """
}
