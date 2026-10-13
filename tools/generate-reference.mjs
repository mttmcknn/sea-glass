/**
 * Regenerate native-port goldens directly from the approved web renderer.
 * Run: npm ci --prefix tools && npm run --prefix tools generate-reference
 * The renderer itself is imported unmodified; the trailing export only exposes
 * its pure internals to this development-only oracle.
 */
import { createCanvas, Path2D } from '@napi-rs/canvas';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = resolve(root, 'sea-glass/src/jvmTest/resources/reference');
const source = await readFile(resolve(root, 'web/dist/sea-glass.mjs'), 'utf8');
globalThis.Path2D = Path2D;
globalThis.document = { createElement: name => {
  if (name !== 'canvas') throw new Error(`Unexpected element: ${name}`);
  return createCanvas(1, 1);
} };
const oracle = await import(`data:text/javascript;base64,${Buffer.from(source + '\nexport { makeTexture, getGeometryField, prepareOptics, shadeBody, drawMaterial, contourPath, PAD };').toString('base64')}`);
const { DEFAULT_SETTINGS, PALETTES, makeTexture, getGeometryField, prepareOptics, shadeBody, drawMaterial, contourPath, PAD } = oracle;
const cases = [
  { id: 'lagoon-rounded', width: 96, height: 40, ratio: 1, shape: 'rounded', palette: 'lagoon', settings: {} },
  { id: 'mint-disc', width: 40, height: 40, ratio: 2, shape: 'disc', palette: 'mint', settings: { seed: 73 } },
  { id: 'lilac-capsule', width: 120, height: 36, ratio: 1.5, shape: 'capsule', palette: 'lilac', settings: { seed: 4294967295, tumble: 0.055, thickness: 10, bulge: 0.8, frost: 0.8, grain: 0.7, transmission: 0.8 } },
  { id: 'amber-portrait', width: 36, height: 60, ratio: 1, shape: 'rounded', palette: 'amber', settings: { seed: 0, tumble: 0, thickness: 0, bulge: 0, frost: 0, grain: 0, elevation: 0, transmission: 0 } },
];
const textureKeys = ['noise', 'clouds', 'roughX', 'roughY'];
const geometryKeys = ['profile', 'gradX', 'gradY', 'roundProfile', 'roundGradX', 'roundGradY'];
const opticsKeys = ['normals', 'colors', 'dome', 'shapeY', 'shapeZ'];
const lights = [0, 180, 315];
await mkdir(output, { recursive: true });
async function properties(path, values) {
  await writeFile(path, Object.entries(values).map(([key, value]) => `${key}=${Array.isArray(value) ? value.join(',') : value}`).join('\n') + '\n');
}
async function floats(path, arrays) {
  const buffer = Buffer.alloc(arrays.reduce((n, a) => n + a.length, 0) * 4);
  let index = 0;
  for (const array of arrays) for (const value of array) buffer.writeFloatLE(value, index++ * 4);
  await writeFile(path, buffer);
}
function rasterMask(points, width, height, scale) {
  const canvas = createCanvas(width + 2, height + 2);
  const ctx = canvas.getContext('2d');
  ctx.setTransform(scale, 0, 0, scale, 1, 1);
  ctx.fillStyle = '#fff'; ctx.fill(contourPath(points));
  return Uint8Array.from(ctx.getImageData(0, 0, width + 2, height + 2).data.filter((_, i) => i % 4 === 3));
}
function fullRender(width, height, ratio, shape, settings, palette, texture) {
  const canvas = createCanvas(Math.ceil((width + PAD * 2) * ratio), Math.ceil((height + PAD * 2) * ratio));
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  drawMaterial(ctx, width, height, shape, settings, palette, texture);
  return canvas;
}
for (const spec of cases) {
  const settings = { ...DEFAULT_SETTINGS, ...spec.settings };
  const palette = PALETTES[spec.palette];
  const texture = makeTexture(spec.width, spec.height, spec.ratio, settings.seed);
  const points = oracle.buildContour(spec.width, spec.height, spec.shape, settings.tumble, settings.seed);
  const field = getGeometryField(spec.width, spec.height, spec.shape, settings.tumble, settings.seed, texture);
  const optics = prepareOptics(field, settings, palette);
  const directory = resolve(output, spec.id);
  await mkdir(directory, { recursive: true });
  await properties(resolve(directory, 'case.properties'), {
    width: spec.width, height: spec.height, ratio: spec.ratio, shape: spec.shape,
    palette: spec.palette, body: palette.body.slice(1), edge: palette.edge.slice(1), ink: palette.ink.slice(1),
    pixelWidth: texture.width, pixelHeight: texture.height,
    ...settings,
    contour: points.flatMap(p => [p.x, p.y]),
    segments: oracle.contourSegments(points).flatMap(s => [s.start, s.control1, s.control2, s.end].flatMap(p => [p.x, p.y])),
  });
  await writeFile(resolve(directory, 'mask.u8'), rasterMask(points, texture.width, texture.height, texture.scale));
  await writeFile(resolve(directory, 'alpha.u8'), field.alpha);
  await floats(resolve(directory, 'texture.f32le'), textureKeys.map(key => texture[key]));
  await floats(resolve(directory, 'geometry.f32le'), geometryKeys.map(key => field[key]));
  await floats(resolve(directory, 'optics.f32le'), opticsKeys.map(key => optics[key]));
  for (const light of lights) {
    const angle = light * Math.PI / 180;
    shadeBody(field, { ...settings, light }, palette, Math.sin(angle), -Math.cos(angle));
    await writeFile(resolve(directory, `body-${light}.rgba`), field.image.data);
    const canvas = fullRender(spec.width, spec.height, spec.ratio, spec.shape, { ...settings, light }, palette, texture);
    await writeFile(resolve(directory, `material-${light}.png`), canvas.toBuffer('image/png'));
  }
}
const rng = {};
for (const seed of [0, 1, 42, 4294967295]) {
  const random = oracle.seededRandom(seed);
  rng[`seed.${seed}`] = Array.from({ length: 24 }, () => random());
}
await properties(resolve(output, 'random.properties'), rng);

// A broad rectangle avoids quantization artifacts in a one-pixel impulse.
// Its edge is fixed in device coordinates, so only filter scaling can change.
const blurProfiles = {};
for (const scale of [1, 2, 2.5]) {
  const canvas = createCanvas(300, 300), ctx = canvas.getContext('2d');
  ctx.setTransform(scale, 0, 0, scale, 0, 0);
  ctx.filter = 'blur(4px)'; ctx.fillStyle = '#fff';
  ctx.fillRect(100 / scale, 50 / scale, 100 / scale, 200 / scale);
  const pixels = ctx.getImageData(0, 0, 300, 300).data;
  blurProfiles[`scale.${scale}`] = Array.from({ length: 41 }, (_, i) => pixels[(150 * 300 + 80 + i) * 4 + 3]);
}
await properties(resolve(output, 'blur.properties'), blurProfiles);
await properties(resolve(output, 'manifest.properties'), {
  format: 1,
  sourceSha256: createHash('sha256').update(source).digest('hex'),
  canvas: '@napi-rs/canvas@0.1.100',
  cases: cases.map(spec => spec.id),
  lights, textureFields: textureKeys, geometryFields: geometryKeys, opticsFields: opticsKeys,
});

// Larger samples are intended for visual comparison with native screenshots.
const gallery = createCanvas(900, 580), galleryCtx = gallery.getContext('2d');
galleryCtx.fillStyle = '#fff'; galleryCtx.fillRect(0, 0, 900, 580);
for (const [row, name] of ['mint', 'lagoon'].entries()) {
  const settings = { ...DEFAULT_SETTINGS, seed: 42 };
  const shapes = [{ w: 108, h: 108, x: 90, shape: 'disc' }, { w: 320, h: 108, x: 275, shape: 'rounded' }];
  for (const {w, h, x, shape} of shapes) {
    const texture = makeTexture(w, h, 2, settings.seed);
    const image = fullRender(w, h, 2, shape, settings, PALETTES[name], texture);
    galleryCtx.drawImage(image, x - PAD, 75 + row * 230 - PAD, (w + PAD * 2), (h + PAD * 2));
  }
}
await writeFile(resolve(output, 'web-reference.png'), gallery.toBuffer('image/png'));
console.log(`Generated ${cases.length} fixture sets from ${createHash('sha256').update(source).digest('hex').slice(0, 12)} in ${output}`);
