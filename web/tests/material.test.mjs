import test from 'node:test';
import assert from 'node:assert/strict';
import {buildContour,seededRandom} from '../dist/sea-glass.mjs';
import {normalizeRecipe,applyPreset,materialSettings,exportRecipe} from '../dist/state.mjs';

test('contours stay deterministic and inside layout bounds across useful sizes and seeds',()=>{
  for(const [w,h] of [[450,82],[270,88],[76,76],[25,25],[218,76]])for(const shape of ['capsule','rounded','disc'])for(const seed of [0,42,311,2147483000])for(const tumble of [0,.025,.065]){
    const a=buildContour(w,h,shape,tumble,seed);
    assert.deepEqual(a,buildContour(w,h,shape,tumble,seed));
    a.forEach(p=>{assert.ok(Number.isFinite(p.x)&&Number.isFinite(p.y));assert.ok(p.x>=0&&p.y>=0&&p.x<=w&&p.y<=h);});
  }
});
test('tumbled outlines differ between pieces while untumbled geometry stays regular',()=>{
  assert.notDeepEqual(buildContour(100,100,'disc',.04,42),buildContour(100,100,'disc',.04,43));
  assert.deepEqual(buildContour(100,100,'disc',0,42),buildContour(100,100,'disc',0,43));
  const a=seededRandom(72),b=seededRandom(72);
  assert.deepEqual(Array.from({length:20},()=>a()),Array.from({length:20},()=>b()));
});
test('restored recipes reject invalid values and preserve valid boundaries',()=>{
  const value=normalizeRecipe({palette:'missing',settings:{tumble:4,frost:-1,thickness:Infinity,seed:'bad',light:1000}});
  assert.equal(value.palette,'mix');assert.equal(value.settings.tumble,.065);assert.equal(value.settings.frost,0);assert.equal(value.settings.thickness,6);assert.equal(value.settings.seed,42);assert.equal(value.settings.light,360);
  assert.deepEqual(normalizeRecipe(null),normalizeRecipe());
});
test('presets preserve piece identity and color, and frost toggle keeps the editable recipe',()=>{
  const r=normalizeRecipe({palette:'amber',settings:{seed:88},frostEnabled:false});
  const next=applyPreset(r,'tumbled');
  assert.equal(next.settings.seed,88);assert.equal(next.palette,'amber');assert.equal(next.frostEnabled,true);
  const off=materialSettings(r,25);assert.equal(off.seed,113);assert.equal(off.frost,0);assert.equal(off.grain,0);assert.equal(r.settings.frost,.55);
  const output=exportRecipe(r);assert.equal(output.palette,'amber');assert.equal(output.surfaceFrost,false);assert.equal(output.seed,88);
});

test('saved recipes gain transmission without losing their old settings',()=>{
  const restored=normalizeRecipe({palette:'lilac',settings:{seed:100,tumble:.04,frost:.72,light:180}});
  assert.equal(restored.settings.transmission,.58);
  assert.equal(restored.settings.seed,100);assert.equal(restored.settings.light,180);assert.equal(restored.palette,'lilac');
  assert.equal(normalizeRecipe({settings:{transmission:3}}).settings.transmission,1);
  assert.equal(normalizeRecipe({settings:{transmission:-1}}).settings.transmission,0);
  const settings=materialSettings(restored);assert.equal(settings.transmission,.58);
  assert.equal(exportRecipe(restored).transmission,.58);
});

test('bulge is bounded and older recipes gain rounded volume',()=>{
  const restored=normalizeRecipe({settings:{thickness:4,seed:91},palette:'mint'});
  assert.equal(restored.settings.bulge,.65);assert.equal(restored.settings.thickness,4);assert.equal(restored.settings.seed,91);
  assert.equal(normalizeRecipe({settings:{bulge:-3}}).settings.bulge,0);
  assert.equal(normalizeRecipe({settings:{bulge:3}}).settings.bulge,1);
  assert.equal(applyPreset(restored,'tumbled').settings.bulge,.8);
  assert.equal(exportRecipe(restored).bulge,.65);
});
