export const PRESETS = Object.freeze({
  reference: { frost: .55, grain: .48, tumble: .025, thickness: 6, bulge:.65, elevation: 8, light: 315, transmission:.58 },
  tumbled: { frost: .72, grain: .68, tumble: .055, thickness: 9, bulge:.80, elevation: 7, light: 315, transmission:.48 },
  quiet: { frost: .38, grain: .23, tumble: .012, thickness: 3, bulge:.38, elevation: 4, light: 315, transmission:.66 },
});
const bounds = { frost:[0,1], grain:[0,1], tumble:[0,.065], thickness:[0,14], bulge:[0,1], elevation:[0,18], light:[0,360], transmission:[0,1], seed:[0,2147483647] };
export function normalizeRecipe(value = {}) {
  const fallback = {...PRESETS.reference, seed:42};
  const input = value && typeof value === 'object' ? value : {};
  const settings = Object.fromEntries(Object.entries(bounds).map(([key,[min,max]]) => {
    const number = Number(input.settings?.[key]);
    return [key, Number.isFinite(number) ? Math.max(min,Math.min(max,number)) : fallback[key]];
  }));
  settings.seed = Math.round(settings.seed);
  return {settings,palette:['mix','lagoon','lilac','rose','amber','mint'].includes(input.palette)?input.palette:'mix',frostEnabled:input.frostEnabled!==false};
}
export function applyPreset(recipe, name) {
  if (!PRESETS[name]) return normalizeRecipe(recipe);
  return normalizeRecipe({...recipe, settings:{...recipe.settings,...PRESETS[name]}, frostEnabled:true});
}
export function materialSettings(recipe, elementSeed = 0) {
  return {...recipe.settings,seed:(recipe.settings.seed+elementSeed)>>>0,...(!recipe.frostEnabled?{frost:0,grain:0}:{})};
}
export function exportRecipe(recipe) {
  return { name:'SeaGlassMaterial', version:3, units:'CSS px; irregularity is a fraction of the shorter dimension', palette:recipe.palette, surfaceFrost:recipe.frostEnabled, ...recipe.settings };
}
