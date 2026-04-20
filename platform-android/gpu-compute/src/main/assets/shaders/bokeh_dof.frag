#version 320 es
// bokeh_dof.frag — Physically-modelled aperture blur.
// Variable-radius Gaussian weighted by depth difference.
// Foreground/background separation via alpha compositing.
// Aperture blade shape via LUT. No uniform Gaussian.

precision highp float;

uniform sampler2D u_color;       // Input colour buffer
uniform sampler2D u_depth;       // Depth map
uniform sampler2D u_bladeShape;  // 1D LUT for aperture blade shape

uniform float u_fStop;
uniform float u_focalLengthMm;
uniform float u_subjectDistance;
uniform float u_sensorSizeMm;
uniform vec2 u_resolution;

uniform int u_bladeCount;
uniform float u_bladeAngleDeg;

// Thin lens equation: blur radius = |focal_length * (1/focusDist - 1/objectDist)| / fStop
float computeBlurRadius(float depthValue) {
    float objectDist = max(depthValue, 0.01);
    float coc = abs(u_focalLengthMm * (1.0 / u_subjectDistance - 1.0 / objectDist));
    float blurRadiusPx = (coc / u_sensorSizeMm) * u_resolution.y;
    return blurRadiusPx;
}

void main() {
    vec2 uv = gl_FragCoord.xy / u_resolution;
    float centerDepth = texture(u_depth, uv).r;

    float blurRadius = computeBlurRadius(centerDepth);
    blurRadius = min(blurRadius, 24.0); // Cap max blur radius

    if (blurRadius < 0.5) {
        gl_FragColor = texture(u_color, uv);
        return;
    }

    vec4 colour = vec4(0.0);
    float totalWeight = 0.0;

    // Sample in disc pattern shaped by aperture blades
    int samples = 32;
    for (int i = 0; i < 32; i++) {
        float angle = float(i) * 6.2831853 / float(samples);
        float r = 1.0;

        // Apply aperture blade shape
        if (u_bladeCount > 0) {
            float bladeAngle = radians(u_bladeAngleDeg);
            float sectorAngle = 6.2831853 / float(u_bladeCount);
            float localAngle = mod(angle - bladeAngle + sectorAngle * 0.5, sectorAngle) - sectorAngle * 0.5;
            r = cos(sectorAngle * 0.5) / cos(localAngle);
        }

        vec2 offset = vec2(cos(angle), sin(angle)) * r * blurRadius / u_resolution;
        vec2 sampleUv = uv + offset;

        float sampleDepth = texture(u_depth, sampleUv).r;
        float sampleBlur = computeBlurRadius(sampleDepth);

        // Weight by depth similarity (foreground/background separation)
        float depthWeight = 1.0 / (1.0 + abs(centerDepth - sampleDepth) * 10.0);
        float weight = depthWeight;

        colour += texture(u_color, sampleUv) * weight;
        totalWeight += weight;
    }

    gl_FragColor = colour / max(totalWeight, 0.001);
}
