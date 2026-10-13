/**
 * Sea glass is a material, not an image: one seeded contour drives every layer.
 * All lengths are CSS pixels. The texture lives in material coordinates and is
 * cached independently of controls, so changing the light never changes a pit.
 */
export const PALETTES = Object.freeze({
  lagoon: { name: 'Lagoon', body: '#B5D0EB', edge: '#D6E9FF', ink: '#2F647C' },
  lilac: { name: 'Lilac', body: '#E1CDE8', edge: '#FBE8F7', ink: '#71388B' },
  rose: { name: 'Rose', body: '#EECED6', edge: '#FFF0F1', ink: '#963F59' },
  amber: { name: 'Amber', body: '#F0D9BE', edge: '#FFFCE0', ink: '#865733' },
  mint: { name: 'Mint', body: '#C0DED0', edge: '#E6FFF0', ink: '#396C5A' },
});

export const DEFAULT_SETTINGS = Object.freeze({
  frost: 0.55, grain: 0.48, tumble: 0.025,
  thickness: 6, bulge: 0.65, elevation: 8, light: 315, transmission: 0.58, seed: 42,
});

const PAD = 48;
const TAU = Math.PI * 2;
const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, n));
export function seededRandom(seed) {
  let value = (Number(seed) || 0) >>> 0;
  return () => {
    value += 0x6D2B79F5;
    let n = value;
    n = Math.imul(n ^ n >>> 15, n | 1);
    n ^= n + Math.imul(n ^ n >>> 7, n | 61);
    return ((n ^ n >>> 14) >>> 0) / 4294967296;
  };
}

/** Pure, deterministic geometry, also usable from Node or another renderer. */
export function buildContour(width, height, shape = 'rounded', tumble = 0.025, seed = 42, count = 160) {
  const w = Math.max(1, Number(width));
  const h = Math.max(1, Number(height));
  const short = Math.min(w, h);
  const amount = clamp(Number(tumble) || 0, 0, 0.065);
  const rand = seededRandom(seed);
  const phase = [rand() * TAU, rand() * TAU, rand() * TAU];
  const strength = [0.70 + rand() * 0.10, 0.18 + rand() * 0.04];
  const samples = Math.max(64, Math.round(count));
  const base = [];
  const ripple = t => Math.sin(t * 2 + phase[0]) * strength[0]
    + Math.sin(t * 3 + phase[1]) * strength[1];
  // Nearly half-round corners make each piece a worn pebble, rather than a
  // rounded box. Smooth the base before offsetting: a stadium's line/arc joins
  // have continuous tangents but discontinuous curvature and normal slopes.
  const radius = shape === 'capsule' ? short / 2 : short * 0.46;
  const arc = Math.PI * radius / 2;
  const horizontal = Math.max(0, w - radius * 2);
  const vertical = Math.max(0, h - radius * 2);
  const lengths = [horizontal, arc, vertical, arc, horizontal, arc, vertical, arc];
  const perimeter = lengths.reduce((sum, n) => sum + n, 0);
  for (let i = 0; i < samples; i++) {
    const t = i / samples * TAU;
    let x, y, nx, ny;
    if (shape === 'disc') {
      const angle = t - Math.PI / 2;
      x = w / 2 + Math.cos(angle) * w / 2;
      y = h / 2 + Math.sin(angle) * h / 2;
      nx = Math.cos(angle) / w;
      ny = Math.sin(angle) / h;
      const length = Math.hypot(nx, ny);
      nx /= length; ny /= length;
    } else {
      let distance = i / samples * perimeter;
      let segment = 0;
      while (segment < 7 && distance >= lengths[segment]) {
        distance -= lengths[segment]; segment++;
      }
      const unit = lengths[segment] ? distance / lengths[segment] : 0;
      if (segment === 0) { x = radius + distance; y = 0; nx = 0; ny = -1; }
      if (segment === 2) { x = w; y = radius + distance; nx = 1; ny = 0; }
      if (segment === 4) { x = w - radius - distance; y = h; nx = 0; ny = 1; }
      if (segment === 6) { x = 0; y = h - radius - distance; nx = -1; ny = 0; }
      if (segment % 2 === 1) {
        const corner = (segment - 1) / 2;
        const angle = -Math.PI / 2 + corner * Math.PI / 2 + unit * Math.PI / 2;
        const centers = [[w - radius, radius], [w - radius, h - radius], [radius, h - radius], [radius, radius]];
        nx = Math.cos(angle); ny = Math.sin(angle);
        x = centers[corner][0] + nx * radius;
        y = centers[corner][1] + ny * radius;
      }
    }
    base.push({x,y});
  }
  const ellipseA = w / 2, ellipseB = h / 2;
  const curveLength = shape === 'disc'
    ? Math.PI * (3 * (ellipseA + ellipseB) - Math.sqrt((3 * ellipseA + ellipseB) * (ellipseA + 3 * ellipseB)))
    : perimeter;
  const sigma = Math.max(0.7, short * 0.065 / (curveLength / samples));
  const reach = Math.ceil(sigma * 3);
  const weights = Array.from({length:reach * 2 + 1},(_,i)=>Math.exp(-Math.pow(i-reach,2)/(2*sigma*sigma)));
  const weightSum = weights.reduce((sum,n)=>sum+n,0);
  const smooth = base.map((_,i)=>weights.reduce((point,weight,k)=>{
    const neighbor=base[(i+k-reach+samples)%samples];
    point.x+=neighbor.x*weight/weightSum;point.y+=neighbor.y*weight/weightSum;
    return point;
  },{x:0,y:0}));
  const points=smooth.map((point,i)=>{
    const prev=smooth[(i-1+samples)%samples],next=smooth[(i+1)%samples];
    const dx=next.x-prev.x,dy=next.y-prev.y,length=Math.hypot(dx,dy);
    const displacement=short*amount*ripple(i/samples*TAU);
    return {x:point.x+dy/length*displacement,y:point.y-dx/length*displacement};
  });
  // Fit the worn outline to its layout bounds; touch geometry stays predictable.
  const minX = Math.min(...points.map(p => p.x));
  const maxX = Math.max(...points.map(p => p.x));
  const minY = Math.min(...points.map(p => p.y));
  const maxY = Math.max(...points.map(p => p.y));
  const inset = Math.min(0.45, short * 0.005);
  return points.map(p => ({
    x: inset + (p.x - minX) / (maxX - minX) * (w - inset * 2),
    y: inset + (p.y - minY) / (maxY - minY) * (h - inset * 2),
  }));
}

/** Periodic uniform B-spline: exact C2 continuity, with no overshoot at joins. */
export function contourSegments(points) {
  const n = points.length;
  return points.map((_,i)=>{
    const a = points[(i - 1 + n) % n], b = points[i];
    const c = points[(i + 1) % n], d = points[(i + 2) % n];
    return {
      start:{x:(a.x+4*b.x+c.x)/6,y:(a.y+4*b.y+c.y)/6},
      control1:{x:(2*b.x+c.x)/3,y:(2*b.y+c.y)/3},
      control2:{x:(b.x+2*c.x)/3,y:(b.y+2*c.y)/3},
      end:{x:(b.x+4*c.x+d.x)/6,y:(b.y+4*c.y+d.y)/6},
    };
  });
}

function contourPath(points) {
  const path = new Path2D();
  const segments=contourSegments(points);
  path.moveTo(segments[0].start.x,segments[0].start.y);
  for(const s of segments)path.bezierCurveTo(s.control1.x,s.control1.y,s.control2.x,s.control2.y,s.end.x,s.end.y);
  path.closePath();
  return path;
}

function rgb(hex) {
  const value = parseInt(hex.slice(1), 16);
  return [value >> 16, value >> 8 & 255, value & 255];
}
function mix(a, b, amount) {
  const from = rgb(a), to = rgb(b);
  return `rgb(${from.map((v, i) => Math.round(v + (to[i] - v) * amount)).join(',')})`;
}
function rgba(hex, alpha) { return `rgba(${rgb(hex).join(',')},${alpha})`; }
function resolvePalette(value) {
  if (value && typeof value === 'object') return value;
  return PALETTES[value] || PALETTES.lagoon;
}
function normalize(settings) {
  return {
    ...settings,
    frost: clamp(Number(settings.frost), 0, 1),
    grain: clamp(Number(settings.grain), 0, 1),
    tumble: clamp(Number(settings.tumble), 0, 0.065),
    thickness: clamp(Number(settings.thickness), 0, 14),
    bulge: clamp(Number(settings.bulge ?? DEFAULT_SETTINGS.bulge), 0, 1),
    elevation: clamp(Number(settings.elevation), 0, 18),
    transmission: clamp(Number(settings.transmission ?? DEFAULT_SETTINGS.transmission), 0, 1),
    light: Number(settings.light) || 0,
    seed: (Number(settings.seed) || 0) >>> 0,
  };
}

// Correlated noise is shared by all lighting states. Frost is a fine uneven
// abrasion field, not independently drawn dark dots or decorative confetti.
function noiseGrid(width, height, spacing, random) {
  const columns = Math.ceil(width / spacing) + 2;
  const rows = Math.ceil(height / spacing) + 2;
  const values = Float32Array.from({ length: columns * rows }, () => random() * 2 - 1);
  return (x, y) => {
    const gx = x / spacing, gy = y / spacing;
    const ix = Math.floor(gx), iy = Math.floor(gy);
    const tx = gx - ix, ty = gy - iy;
    const sx = tx * tx * (3 - 2 * tx), sy = ty * ty * (3 - 2 * ty);
    const a = values[iy * columns + ix], b = values[iy * columns + ix + 1];
    const c = values[(iy + 1) * columns + ix], d = values[(iy + 1) * columns + ix + 1];
    return (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy;
  };
}

function makeTexture(w, h, ratio, seed) {
  const scale = Math.min(ratio, 2);
  const width = Math.max(1, Math.ceil(w * scale)), height = Math.max(1, Math.ceil(h * scale));
  const rand = seededRandom(seed ^ 0x59BF1D7A);
  const fine = noiseGrid(w + 3, h + 3, 0.68, rand);
  const micro = noiseGrid(w + 3, h + 3, 1.7, rand);
  const worn = noiseGrid(w + 3, h + 3, 7.5, rand);
  const cloud = noiseGrid(w + 3, h + 3, Math.min(w, h) * 0.44, rand);
  const count = width * height;
  const noise = new Float32Array(count), clouds = new Float32Array(count);
  const roughX = new Float32Array(count), roughY = new Float32Array(count);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const i = y * width + x, cx = (x + 0.5) / scale, cy = (y + 0.5) / scale;
    noise[i] = fine(cx, cy) * 0.54 + micro(cx, cy) * 0.32 + worn(cx, cy) * 0.14;
    clouds[i] = cloud(cx, cy) * 0.75 + worn(cx, cy) * 0.25;
    roughX[i] = micro(cx + 0.32, cy) - micro(Math.max(0, cx - 0.32), cy);
    roughY[i] = micro(cx, cy + 0.32) - micro(cx, Math.max(0, cy - 0.32));
  }
  return { scale, width, height, noise, clouds, roughX, roughY };
}

// Exact squared Euclidean distance transform, one linear-time pass per axis.
// This avoids evaluating every contour segment for every shaded pixel.
function distanceLine(input, length, output, sites, separators) {
  let k = 0;
  sites[0] = 0;
  separators[0] = -Infinity; separators[1] = Infinity;
  for (let q = 1; q < length; q++) {
    let cut;
    do {
      const v = sites[k];
      cut = ((input[q] + q * q) - (input[v] + v * v)) / (2 * q - 2 * v);
      if (cut <= separators[k]) k--;
      else break;
    } while (k >= 0);
    k++;
    sites[k] = q; separators[k] = cut; separators[k + 1] = Infinity;
  }
  k = 0;
  for (let q = 0; q < length; q++) {
    while (separators[k + 1] < q) k++;
    const delta = q - sites[k];
    output[q] = delta * delta + input[sites[k]];
  }
}

// Smooth geometry before differentiating, rather than blurring lit pixels.
// This softens the distance field's medial ridge without softening the frost.
function smoothHeight(values, width, height, scale) {
  const sigma=0.85*scale, reach=Math.ceil(sigma*3);
  const weights=Array.from({length:reach*2+1},(_,i)=>Math.exp(-(((i-reach)/sigma)**2)/2));
  const sum=weights.reduce((total,v)=>total+v,0);
  const horizontal=new Float32Array(values.length), result=new Float32Array(values.length);
  for(let y=0;y<height;y++)for(let x=0;x<width;x++){
    let value=0;
    for(let k=-reach;k<=reach;k++)value+=values[y*width+clamp(x+k,0,width-1)]*weights[k+reach];
    horizontal[y*width+x]=value/sum;
  }
  for(let y=0;y<height;y++)for(let x=0;x<width;x++){
    let value=0;
    for(let k=-reach;k<=reach;k++)value+=horizontal[clamp(y+k,0,height-1)*width+x]*weights[k+reach];
    result[y*width+x]=value/sum;
  }
  return result;
}

const geometryFields = new Map();
function getGeometryField(w, h, shape, tumble, seed, texture) {
  const key = `${w}|${h}|${shape}|${tumble}|${seed}|${texture.scale}`;
  if (geometryFields.has(key)) {
    const field = geometryFields.get(key);
    geometryFields.delete(key); geometryFields.set(key, field);
    return field;
  }
  const points = buildContour(w, h, shape, tumble, seed);
  const path = contourPath(points);
  const { width, height, scale } = texture;
  const columns = width + 2, rows = height + 2;
  const mask = document.createElement('canvas');
  mask.width = columns; mask.height = rows;
  const maskCtx = mask.getContext('2d', { willReadFrequently: true });
  maskCtx.setTransform(scale, 0, 0, scale, 1, 1);
  maskCtx.fillStyle = '#fff'; maskCtx.fill(path);
  const coverage = maskCtx.getImageData(0, 0, columns, rows).data;
  const distances = new Float32Array(columns * rows);
  for (let i = 0; i < distances.length; i++) distances[i] = coverage[i * 4 + 3] > 127 ? 1e12 : 0;
  const longest = Math.max(columns, rows);
  const input = new Float64Array(longest), output = new Float64Array(longest);
  const sites = new Int32Array(longest), separators = new Float64Array(longest + 1);
  for (let y = 0; y < rows; y++) {
    input.set(distances.subarray(y * columns, (y + 1) * columns));
    distanceLine(input, columns, output, sites, separators);
    distances.set(output.subarray(0, columns), y * columns);
  }
  for (let x = 0; x < columns; x++) {
    for (let y = 0; y < rows; y++) input[y] = distances[y * columns + x];
    distanceLine(input, rows, output, sites, separators);
    for (let y = 0; y < rows; y++) distances[y * columns + x] = Math.max(0, Math.sqrt(output[y]) - 0.5) / scale;
  }
  const count = width * height;
  const profile = new Float32Array(count), gradX = new Float32Array(count), gradY = new Float32Array(count);
  const roundProfile = new Float32Array(count), roundGradX = new Float32Array(count), roundGradY = new Float32Array(count);
  const alpha = new Uint8Array(count);
  const short = Math.min(w, h);
  let maxDistance=0;
  for(const distance of distances)maxDistance=Math.max(maxDistance,distance);
  maxDistance=Math.max(1,maxDistance);
  const at = (x, y) => distances[clamp(y, 0, rows - 1) * columns + clamp(x, 0, columns - 1)];
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const i = y * width + x, mx = x + 1, my = y + 1;
    alpha[i] = coverage[(my * columns + mx) * 4 + 3];
    // An irregular broad shoulder (about 20% of the shorter dimension) flows
    // into a largely flat face. Tanh has no shoulder/face join or inner border.
    const roll = short * (0.205 + texture.clouds[i] * 0.023);
    const dome = Math.tanh(at(mx, my) / roll);
    const slope = (1 - dome * dome) / roll;
    profile[i] = dome;
    const dx = at(mx + 2, my) - at(mx - 2, my);
    const dy = at(mx, my + 2) - at(mx, my - 2);
    const direction = Math.hypot(dx, dy);
    // Normalize the distance gradient so mask sampling does not flatten the
    // outermost pixel and accidentally form a bright line outside a shoulder.
    gradX[i] = direction > 0.001 ? dx / direction * slope : 0;
    gradY[i] = direction > 0.001 ? dy / direction * slope : 0;
    // A semielliptical cross section turns all the way around the shoulder.
    // The former sine arch had a finite silhouette slope: its normals stayed
    // mostly face-on, so the glass read as a flat face with colored margins.
    const q=clamp(at(mx,my)/maxDistance,0,1);
    const arch=Math.sqrt(q*(2-q));
    const roundSlope=(1-q)/(maxDistance*Math.max(arch,0.035));
    // A gentle longitudinal crown makes a long control a rounded object, not
    // a perfectly extruded cylinder. It vanishes at the same worn outline.
    const u=(x+0.5)/width;
    const lengthArch=0.86+0.14*Math.sin(u*Math.PI)**2;
    const lengthSlope=0.14*Math.PI/w*Math.sin(u*TAU);
    roundProfile[i]=arch*lengthArch;
    roundGradX[i]=(direction>0.001?dx/direction*roundSlope*lengthArch:0)+arch*lengthSlope;
    roundGradY[i]=direction>0.001?dy/direction*roundSlope*lengthArch:0;
  }
  const softened=smoothHeight(roundProfile,width,height,scale);
  const heightAt=(x,y)=>softened[clamp(y,0,height-1)*width+clamp(x,0,width-1)];
  for(let y=0;y<height;y++)for(let x=0;x<width;x++){
    const i=y*width+x;
    const blend=clamp((at(x+1,y+1)-2)/3,0,1);
    const weight=blend*blend*(3-2*blend);
    roundProfile[i]+=(softened[i]-roundProfile[i])*weight;
    roundGradX[i]+=((heightAt(x+2,y)-heightAt(x-2,y))*scale/4-roundGradX[i])*weight;
    roundGradY[i]+=((heightAt(x,y+2)-heightAt(x,y-2))*scale/4-roundGradY[i])*weight;
  }
  const canvas = document.createElement('canvas');
  canvas.width = width; canvas.height = height;
  const context = canvas.getContext('2d');
  const image = context.createImageData(width, height);
  const field = { key, path, profile, gradX, gradY, roundProfile, roundGradX, roundGradY, alpha, canvas, context, image, texture, opticalKey: '', optical: null };
  geometryFields.set(key, field);
  // Bound cache growth while preserving a typical playground's visible set.
  if (geometryFields.size > 32) geometryFields.delete(geometryFields.keys().next().value);
  return field;
}

function prepareOptics(field, settings, palette) {
  const { thickness, bulge, frost, grain, transmission } = settings;
  const key = `${thickness}|${bulge}|${frost}|${grain}|${transmission}|${palette.body}|${palette.edge}|${palette.ink}`;
  if (field.opticalKey === key) return field.optical;
  const n = field.alpha.length;
  const normals = new Float32Array(n * 3), colors = new Float32Array(n * 3);
  const shapeY=new Float32Array(n), shapeZ=new Float32Array(n);
  const { profile, gradX, gradY, roundProfile, roundGradX, roundGradY, texture } = field;
  const dome = new Float32Array(n);
  const body = rgb(palette.body), edge = rgb(palette.edge);
  const short = Math.min(texture.width, texture.height) / texture.scale;
  const rise = 0.9 + Math.min(thickness * 1.55, short * 0.22) + bulge * short * 0.34;
  for (let i = 0; i < n; i++) {
    const cloud = texture.clouds[i], rough = grain * (0.025 + frost * 0.04);
    dome[i]=profile[i]+(roundProfile[i]-profile[i])*bulge;
    const slopeX=gradX[i]+(roundGradX[i]-gradX[i])*bulge;
    const slopeY=gradY[i]+(roundGradY[i]-gradY[i])*bulge;
    const shapeInv=1/Math.hypot(slopeX*rise,slopeY*rise,1);
    shapeY[i]=-slopeY*rise*shapeInv; shapeZ[i]=shapeInv;
    const nx = -slopeX * rise + texture.roughX[i] * rough;
    const ny = -slopeY * rise + texture.roughY[i] * rough;
    const inv = 1 / Math.hypot(nx, ny, 1);
    normals[i * 3] = nx * inv; normals[i * 3 + 1] = ny * inv; normals[i * 3 + 2] = inv;
    // Optical depth lives in the body. Thin rounded shoulders transmit more;
    // the broad interior retains color instead of an absorbing perimeter rim.
    // The small vertical bias preserves richer upper color beneath reflections.
    const travel = Math.floor(i / texture.width) / Math.max(1, texture.height - 1);
    const flow = travel * travel * (3 - 2 * travel);
    const depth = (1.05 - flow * transmission * 0.13)
      * (0.70 + dome[i] * (0.52 + thickness * 0.012))
      * (1+(1-shapeInv)*Math.max(0,-shapeY[i])*0.28) * (1 + cloud * 0.035);
    const weathering = frost * (0.06 + (cloud + 1) * 0.025);
    for (let c = 0; c < 3; c++) {
      const tint = body[c] * 0.96 + edge[c] * 0.04;
      const absorbed = 255 - (255 - tint) * depth;
      colors[i * 3 + c] = absorbed + (255 - absorbed) * weathering;
    }
  }
  field.opticalKey = key;
  field.optical = { normals, colors, dome, shapeY, shapeZ };
  return field.optical;
}

function shadeBody(field, settings, palette, lx, ly) {
  const { normals, colors, dome, shapeY, shapeZ } = prepareOptics(field, settings, palette);
  const { frost, grain, transmission, thickness, bulge } = settings;
  const { alpha, texture, image } = field;
  const pixels = image.data;
  const lightZ = 0.52, lightSide = Math.sqrt(1 - lightZ * lightZ);
  const halfLength = Math.hypot(lx * lightSide, ly * lightSide, lightZ + 1);
  const hx = lx * lightSide / halfLength, hy = ly * lightSide / halfLength, hz = (lightZ + 1) / halfLength;
  const exponent = 12 + (1 - frost) * 18;
  // A wide softbox has more extent along its tangent than its elevation.
  // That produces a broad reflection even on a capsule at diagonal angles.
  const reflectionWidth=0.78+frost*0.24, reflectionHeight=0.46+frost*0.24;
  const edge = rgb(palette.edge);
  for (let i = 0; i < alpha.length; i++) {
    const p = i * 4, n = i * 3;
    if (!alpha[i]) { pixels[p + 3] = 0; continue; }
    const nx = normals[n], ny = normals[n + 1], nz = normals[n + 2];
    const diffuse = Math.max(0, nx * lx * lightSide + ny * ly * lightSide + nz * lightZ);
    // Reflect the viewing direction through the actual curved normal. A soft
    // environment lobe bends across the crest and endcaps as its angle moves;
    // it is not a screen-space gradient or a stroked highlight on the edge.
    const roughness = texture.noise[i];
    const rx=2*nx*nz, ry=2*ny*nz, rz=2*nz*nz-1;
    const reflectedLight=Math.max(0,rx*lx*lightSide+ry*ly*lightSide+rz*lightZ);
    const across=-ly*rx+lx*ry;
    const along=-lightZ*(lx*rx+ly*ry)+lightSide*rz;
    const reflection=Math.exp(-((across/reflectionWidth)**2+(along/reflectionHeight)**2))
      *clamp(reflectedLight/0.30,0,1)*(0.15+bulge*0.12)*(0.82+frost*0.18);
    const scatter = Math.pow(Math.max(0, nx * hx + ny * hy + nz * hz), exponent) * (0.008 + frost * 0.012);
    // Soft overhead illumination spreads across the upper turn. Its center is
    // inside the silhouette, leaving a denser colored body below the shoulder.
    const upperTurn=Math.exp(-(((ny+0.40)/0.38)**2))*Math.pow(nz,0.55)
      *(0.10+bulge*0.14)*(0.85+frost*0.15);
    const palePits = Math.pow(Math.max(0, roughness + 0.20), 2) * grain * (5 + frost * 10) * (0.65 + diffuse * 0.35);
    const matte = roughness * grain * (0.012 + frost * 0.016);
    // Body transmission flows down from the overhead source independently of
    // the adjustable angle, which moves reflections across the curved surface.
    const travel = Math.floor(i / texture.width) / Math.max(1, texture.height - 1);
    const flow = travel * travel * (3 - 2 * travel);
    // Light exits through the curved lower shoulder. The normal, including its
    // side-facing part at the corners, makes the exit wrap around the volume.
    // This is a broad interior roll into a luminous edge, never a contour stroke.
    const grazing=Math.sqrt(Math.max(0,1-shapeZ[i]*shapeZ[i]));
    const lowerFacing=clamp((shapeY[i]+0.16)/0.80,0,1);
    const shoulder=Math.pow(grazing,2.3)*lowerFacing*lowerFacing*(3-2*lowerFacing);
    const exitAmount=clamp(transmission*(0.10*flow+1.65*shoulder)
      *(0.96-thickness*0.005),0,0.96);
    // A last soft catch appears only where the lower curve turns away from
    // the viewer. Keep this confined to grazing normals, preserving the tint
    // through the rest of the shoulder instead of bleaching its whole band.
    const exitCatch=Math.pow(1-shapeZ[i],2.2)*lowerFacing*0.85;
    for (let c = 0; c < 3; c++) {
      let value = colors[n + c];
      value += (255 - value) * (reflection+scatter+upperTurn);
      const exitColor=edge[c]+(255-edge[c])*exitCatch;
      value += (exitColor - value) * exitAmount;
      value += (palePits + matte * 255)*(1-exitAmount*0.45);
      pixels[p + c] = clamp(Math.round(value), 0, 255);
    }
    pixels[p + 3] = alpha[i];
  }
  field.context.putImageData(image, 0, 0);
  return field.canvas;
}

function drawMaterial(ctx, w, h, shape, settings, palette, texture) {
  const { frost, tumble, seed, thickness, bulge, elevation, light, transmission: transmittance = 0.58 } = settings;
  const field = getGeometryField(w, h, shape, tumble, seed, texture);
  const path = field.path;
  const theta = light * Math.PI / 180;
  const lx = Math.sin(theta), ly = -Math.cos(theta);
  const short=Math.min(w,h);
  ctx.save(); ctx.translate(PAD, PAD);

  // The scene source is fixed overhead: every cast/contact shadow stays below
  // the object. Surface-light angle never moves or flips the ground shadow.
  ctx.save();
  ctx.translate(0, Math.min(16, thickness * 0.40 + elevation * 0.72 + bulge * short*0.03));
  ctx.filter = `blur(${Math.min(6,2.5 + elevation * 0.18 + frost * 0.7 + bulge*short*0.018)}px)`;
  ctx.fillStyle = `rgba(63,67,66,${(0.18 + elevation * 0.001) * (1 - transmittance * 0.82)})`;
  ctx.fill(path); ctx.restore();
  ctx.save();
  ctx.translate(0, Math.min(8, thickness * 0.32 + 0.8));
  ctx.filter = `blur(${1.4 + elevation * 0.065}px)`;
  ctx.fillStyle = `rgba(61,65,63,${0.10 * (1 - transmittance * 0.70)})`;
  ctx.fill(path); ctx.restore();

  // Filtered colored light exits below the body into that same fixed shadow.
  if (transmittance > 0.001) {
    ctx.save();
    const travel = 4 + elevation * 0.56 + short * 0.055;
    ctx.translate(0, Math.min(22, thickness * 0.42 + travel));
    ctx.filter = `blur(${Math.min(8,2.8+short*0.033+frost*1.6)}px)`;
    const pool = ctx.createLinearGradient(0, 0, 0, h);
    // Colored transmitted light dominates the footprint. Keep neutral contact
    // occlusion faint, and retain the hue instead of mixing in dark icon ink.
    const tint = rgb(palette.body).map(v=>Math.round(255-(255-v)*1.35));
    pool.addColorStop(0, `rgba(${tint.join(',')},0)`);
    pool.addColorStop(0.45, `rgba(${tint.join(',')},${transmittance*0.06})`);
    pool.addColorStop(0.75, `rgba(${tint.join(',')},${transmittance*0.48})`);
    pool.addColorStop(1, `rgba(${tint.join(',')},${transmittance*0.94})`);
    ctx.fillStyle = pool; ctx.fill(path); ctx.restore();
  }

  // There is exactly one body and no stroked contour, underside, or face/side
  // seam. Its height field, normal lighting and absorption roll continuously
  // through the same silhouette; DOM text is rendered separately above it.
  ctx.drawImage(shadeBody(field, settings, palette, lx, ly), 0, 0, w, h);
  ctx.restore();
}

/** Mount under existing DOM content. No layout or interaction is intercepted. */
export function mountGlass(element, options = {}) {
  const canvas = document.createElement('canvas');
  canvas.setAttribute('aria-hidden', 'true');
  canvas.className = 'sea-glass-canvas';
  Object.assign(canvas.style, {
    position: 'absolute', pointerEvents: 'none', zIndex: '-1',
    left: `-${PAD}px`, top: `-${PAD}px`,
  });
  element.prepend(canvas);
  let settings = normalize({
    ...DEFAULT_SETTINGS, ...options.settings,
    seed: options.seed ?? options.settings?.seed ?? DEFAULT_SETTINGS.seed,
  });
  let palette = resolvePalette(options.palette || 'lagoon');
  const shape = options.shape || 'rounded';
  let width = 0, height = 0, ratio = 1, texture = null, textureKey = '';
  let frame = 0, destroyed = false;

  function render() {
    frame = 0;
    if (destroyed || width < 1 || height < 1) return;
    const key = `${width}|${height}|${ratio}|${settings.seed}`;
    if (key !== textureKey) {
      texture = makeTexture(width, height, ratio, settings.seed);
      textureKey = key;
    }
    const ctx = canvas.getContext('2d');
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.clearRect(0, 0, width + PAD * 2, height + PAD * 2);
    drawMaterial(ctx, width, height, shape, settings, palette, texture);
    element.style.setProperty('--glass-ink', palette.ink);
  }
  function schedule() { if (!frame && !destroyed) frame = requestAnimationFrame(render); }
  function resize() {
    const newWidth = element.offsetWidth;
    const newHeight = element.offsetHeight;
    const newRatio = Math.min(window.devicePixelRatio || 1, 2.5);
    if (newWidth !== width || newHeight !== height || newRatio !== ratio) {
      width = newWidth; height = newHeight; ratio = newRatio;
      canvas.style.width = `${width + PAD * 2}px`;
      canvas.style.height = `${height + PAD * 2}px`;
      canvas.width = Math.ceil((width + PAD * 2) * ratio);
      canvas.height = Math.ceil((height + PAD * 2) * ratio);
    }
    schedule();
  }
  const observer = new ResizeObserver(resize);
  observer.observe(element);
  resize();
  return {
    update(nextSettings = {}, nextPalette) {
      settings = normalize({ ...settings, ...nextSettings });
      if (nextPalette !== undefined) palette = resolvePalette(nextPalette);
      schedule();
    },
    destroy() {
      destroyed = true;
      observer.disconnect();
      if (frame) cancelAnimationFrame(frame);
      canvas.remove();
      texture = null;
    },
  };
}
