package dev.mttmcknn.seaglass.internal

/**
 * AGSL / SkSL ES2-compatible material body. Draw inside the original vector path.
 * Coordinates and materialSize are local dp after dividing by density.
 * geometryA/B are opaque normalized RGB data, sampled with linear filtering.
 * recipeB.z is SeededRandom(seed).nextDouble(), so nearby integer seeds still
 * decorrelate the procedural grain. Recipe/palette changes only update uniforms;
 * no raster material is rebuilt.
 */
internal val SeaGlassBodyShader = """
    uniform shader geometryA;
    uniform shader geometryB;
    uniform float2 materialSize;
    uniform float fieldScale;
    uniform float density;
    uniform float2 gradientScales;
    uniform float4 recipeA;
    uniform float4 recipeB;
    uniform float3 bodyColor;
    uniform float3 edgeColor;
    uniform float2 lightDirection;
    uniform float3 halfway;

    float hashNoise(float2 point, float salt) {
        float3 p = fract(float3(point.x, point.y, point.x) * 0.1031 + salt);
        p += dot(p, p.yzx + 33.33);
        return fract((p.x + p.y) * p.z) * 2.0 - 1.0;
    }

    float noiseGrid(float2 point, float spacing, float salt) {
        float2 cell = point / spacing;
        float2 index = floor(cell);
        float2 fraction = fract(cell);
        float2 eased = fraction * fraction * (3.0 - 2.0 * fraction);
        float a = hashNoise(index, salt);
        float b = hashNoise(index + float2(1.0, 0.0), salt);
        float c = hashNoise(index + float2(0.0, 1.0), salt);
        float d = hashNoise(index + float2(1.0, 1.0), salt);
        return mix(mix(a, b, eased.x), mix(c, d, eased.x), eased.y);
    }

    // Value plus the analytic gradient of the same cubic interpolation.
    // Four lattice hashes replace the center and four offset evaluations.
    float3 noiseGridGradient(float2 point, float spacing, float salt) {
        float2 cell = point / spacing;
        float2 index = floor(cell);
        float2 fraction = fract(cell);
        float2 eased = fraction * fraction * (3.0 - 2.0 * fraction);
        float2 derivative = 6.0 * fraction * (1.0 - fraction) / spacing;
        float a = hashNoise(index, salt);
        float b = hashNoise(index + float2(1.0, 0.0), salt);
        float c = hashNoise(index + float2(0.0, 1.0), salt);
        float d = hashNoise(index + float2(1.0, 1.0), salt);
        float value = mix(mix(a, b, eased.x), mix(c, d, eased.x), eased.y);
        float dx = mix(b - a, d - c, eased.y) * derivative.x;
        float dy = mix(c - a, d - b, eased.x) * derivative.y;
        return float3(value, dx, dy);
    }

    half4 main(float2 coordinates) {
        float2 position = coordinates / density;
        float3 base = geometryA.eval(position * fieldScale).rgb;
        float3 rounded = geometryB.eval(position * fieldScale).rgb;
        float2 gradient = (base.gb - 0.5) / gradientScales.x;
        float2 roundedGradient = (rounded.gb - 0.5) / gradientScales.y;
        float frost = recipeA.x;
        float grain = recipeA.y;
        float thickness = recipeA.z;
        float bulge = recipeA.w;
        float transmission = recipeB.y;
        float seed = recipeB.z;
        float shortSide = min(materialSize.x, materialSize.y);
        float fine = noiseGrid(position, 0.68, seed + 0.11);
        float3 micro = noiseGridGradient(position, 1.7, seed + 0.29);
        float worn = noiseGrid(position, 7.5, seed + 0.47);
        float cloud = noiseGrid(position, shortSide * 0.44, seed + 0.71) * 0.75 + worn * 0.25;
        float roughness = fine * 0.54 + micro.x * 0.32 + worn * 0.14;
        // The reference differences span 0.64 dp, shortened at the top/left.
        float2 roughGradient = micro.yz * (0.32 + min(position, float2(0.32)));
        float dome = mix(base.r, rounded.r, bulge);
        float2 slope = mix(gradient, roundedGradient, bulge);
        float rise = 0.9 + min(thickness * 1.55, shortSide * 0.22) + bulge * shortSide * 0.34;
        float3 shapeNormal = normalize(float3(-slope * rise, 1.0));
        shapeNormal.z = clamp(shapeNormal.z, 0.0, 1.0);
        float rough = grain * (0.025 + frost * 0.04);
        float3 normal = normalize(float3(-slope * rise + roughGradient * rough, 1.0));
        float travel = clamp(position.y / materialSize.y, 0.0, 1.0);
        float flow = travel * travel * (3.0 - 2.0 * travel);
        float depth = (1.05 - flow * transmission * 0.13)
            * (0.70 + dome * (0.52 + thickness * 0.012))
            * (1.0 + (1.0 - shapeNormal.z) * max(0.0, -shapeNormal.y) * 0.28)
            * (1.0 + cloud * 0.035);
        float weathering = frost * (0.06 + (cloud + 1.0) * 0.025);
        float3 tint = bodyColor * 0.96 + edgeColor * 0.04;
        float3 color = 1.0 - (1.0 - tint) * depth;
        color += (1.0 - color) * weathering;

        float lx = lightDirection.x;
        float ly = lightDirection.y;
        float lightZ = 0.52;
        float lightSide = sqrt(1.0 - lightZ * lightZ);
        float3 light = float3(lx * lightSide, ly * lightSide, lightZ);
        float diffuse = max(0.0, dot(normal, light));
        float3 reflectionNormal = float3(2.0 * normal.xy * normal.z, 2.0 * normal.z * normal.z - 1.0);
        float reflectedLight = max(0.0, dot(reflectionNormal, light));
        float across = -ly * reflectionNormal.x + lx * reflectionNormal.y;
        float along = -lightZ * (lx * reflectionNormal.x + ly * reflectionNormal.y) + lightSide * reflectionNormal.z;
        float acrossScale = across / (0.78 + frost * 0.24);
        float alongScale = along / (0.46 + frost * 0.24);
        float reflection = exp(-(acrossScale * acrossScale + alongScale * alongScale))
            * clamp(reflectedLight / 0.30, 0.0, 1.0) * (0.15 + bulge * 0.12) * (0.82 + frost * 0.18);
        float scatter = pow(max(0.0, dot(normal, halfway)), 12.0 + (1.0 - frost) * 18.0) * (0.008 + frost * 0.012);
        float upperScale = (normal.y + 0.40) / 0.38;
        float upperTurn = exp(-(upperScale * upperScale)) * pow(max(normal.z, 0.000001), 0.55)
            * (0.10 + bulge * 0.14) * (0.85 + frost * 0.15);
        float palePits = pow(max(0.0, roughness + 0.20), 2.0) * grain
            * (5.0 + frost * 10.0) * (0.65 + diffuse * 0.35);
        float matte = roughness * grain * (0.012 + frost * 0.016);
        float grazing = sqrt(max(0.0, 1.0 - shapeNormal.z * shapeNormal.z));
        float lowerFacing = clamp((shapeNormal.y + 0.16) / 0.80, 0.0, 1.0);
        float shoulder = pow(grazing, 2.3) * lowerFacing * lowerFacing * (3.0 - 2.0 * lowerFacing);
        float exitAmount = clamp(transmission * (0.10 * flow + 1.65 * shoulder)
            * (0.96 - thickness * 0.005), 0.0, 0.96);
        float exitCatch = pow(max(0.0, 1.0 - shapeNormal.z), 2.2) * lowerFacing * 0.85;
        color += (1.0 - color) * (reflection + scatter + upperTurn);
        float3 exitColor = edgeColor + (1.0 - edgeColor) * exitCatch;
        color += (exitColor - color) * exitAmount;
        color += (palePits / 255.0 + matte) * (1.0 - exitAmount * 0.45);
        return half4(clamp(color, 0.0, 1.0), 1.0);
    }
""".trimIndent()
