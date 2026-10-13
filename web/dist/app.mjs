import {PALETTES,mountGlass} from './sea-glass.mjs';
import {PRESETS,normalizeRecipe,applyPreset,materialSettings,exportRecipe} from './state.mjs';

const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const paths = {
  share:'<circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="5" r="2.5"/><circle cx="18" cy="19" r="2.5"/><path d="m8.2 10.8 7.6-4.6m-7.6 7 7.6 4.6"/>',
  message:'<path d="M4 4h16v13H9l-5 4V4Z"/><path d="M8 8h8M8 12h8"/>',
  download:'<path d="M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4"/>',
  pencil:'<path d="m16 3 5 5L8 21H3v-5L16 3ZM14 5l5 5M3 21l5-5"/>',
  more:'<circle cx="12" cy="5" r="1.6" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.6" fill="currentColor" stroke="none"/><circle cx="12" cy="19" r="1.6" fill="currentColor" stroke="none"/>',
  plus:'<path d="M12 4v16M4 12h16"/>',
  sun:'<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>',
  moon:'<path d="M20 15.2A8.5 8.5 0 0 1 8.8 4 8.5 8.5 0 1 0 20 15.2Z"/>',
  code:'<path d="m8 6-6 6 6 6m8-12 6 6-6 6M14 3l-4 18"/>',
  reset:'<path d="M3 4v6h6M3.5 9A8 8 0 1 1 4 17"/>',
  sparkles:'<path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Zm7-2v4m-2-2h4"/>',
  pebble:'<path d="M20.5 14c1-4-2-9-6-10.5S4 5 3 10s2 10 7 11 9.5-3 10.5-7Z"/>',
  shuffle:'<path d="M3 5h3c4 0 8 14 12 14h3m-4-4 4 4-4 4M3 19h3c4 0 8-14 12-14h3m-4-4 4 4-4 4"/>',
  pin:'<path d="m8 3 8 0-1 7 4 4H5l4-4-1-7ZM12 14v7"/>',
  close:'<path d="m6 6 12 12M18 6 6 18"/>',
  check:'<path d="m5 12 4 4L19 6"/>',
  bookmark:'<path d="M6 3h12v18l-6-4-6 4V3Z"/>',
  leaf:'<path d="M20 3c1 12-4 18-11 15S3 6 20 3ZM5 21 16 8"/>',
  image:'<rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="8" cy="8" r="2"/><path d="m3 17 5-4 4 3 5-6 4 5"/>',
  bell:'<path d="M5 17h14l-2-3V9a5 5 0 0 0-10 0v5l-2 3ZM10 21h4M12 2v2"/>',
};
function iconMarkup(name) { return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name]||paths.pebble}</svg>`; }
function hydrateIcons(root=document) { root.querySelectorAll('[data-icon]').forEach(el=>el.innerHTML=iconMarkup(el.dataset.icon)); }
hydrateIcons();

const storageKey='sea-glass-playground-v1';
let recipe=normalizeRecipe();
let pinned=null;
let scene='light';
try { const saved=JSON.parse(localStorage.getItem(storageKey)); if(saved){recipe=normalizeRecipe(saved.recipe);pinned=saved.pinned?normalizeRecipe(saved.pinned):null;scene=saved.scene==='dark'?'dark':'light';} } catch {}
const instances=new Map();
let compareInstances=[];
let queued=false;
let toastTimer;
function persist() { try { localStorage.setItem(storageKey,JSON.stringify({recipe,pinned,scene})); } catch {} }
function showToast(message) { clearTimeout(toastTimer);$('#toast').textContent=message;$('#toast').classList.add('visible');toastTimer=setTimeout(()=>$('#toast').classList.remove('visible'),2600); }
function mount(el,overrideRecipe=recipe) {
  const palette=overrideRecipe.palette==='mix'?(el.dataset.palette||'lagoon'):overrideRecipe.palette;
  const extraSeed=Number(el.dataset.seed)||0;
  const renderer=mountGlass(el,{shape:el.dataset.shape||'rounded',palette,seed:overrideRecipe.settings.seed+extraSeed,settings:materialSettings(overrideRecipe,extraSeed)});
  el.addEventListener('pointerdown',()=>{ if(el.matches('button')||el.querySelector('button'))renderer.update({...materialSettings(recipe,extraSeed),elevation:Math.max(0,recipe.settings.elevation-3)},recipe.palette==='mix'?(el.dataset.palette||'lagoon'):recipe.palette); });
  const restore=()=>renderer.update(materialSettings(recipe,extraSeed),recipe.palette==='mix'?(el.dataset.palette||'lagoon'):recipe.palette);
  el.addEventListener('pointerup',restore);el.addEventListener('pointercancel',restore);el.addEventListener('pointerleave',restore);
  return renderer;
}

const shapes=[['disc','amber','04'],['rounded','lagoon','17'],['disc','mint','29'],['rounded','rose','38'],['capsule','lilac','51'],['disc','lagoon','63']];
$('#shape-grid').innerHTML=shapes.map(([shape,palette,seed])=>`<div class="shape-item"><button class="glass" data-glass data-shape="${shape}" data-palette="${palette}" data-seed="${seed}" aria-label="Select ${PALETTES[palette].name} material, seed ${seed}" data-select-palette="${palette}"${shape==='capsule'?' style="width:115px;height:76px"':''}></button><span>PIECE ${seed} / ${PALETTES[palette].name.toUpperCase()}</span></div>`).join('');
$$('[data-glass]').forEach(el=>instances.set(el,mount(el)));

const paletteNames={mix:'Coastal mix',...Object.fromEntries(Object.entries(PALETTES).map(([key,p])=>[key,p.name]))};
$('#palette-options').innerHTML=Object.entries(paletteNames).map(([key,name])=>`<button class="palette-swatch" data-palette-choice="${key}" aria-label="${name}" title="${name}" aria-pressed="false" style="background:${key==='mix'?'conic-gradient(#a7dcee 0deg 90deg,#e8c5f1 90deg 180deg,#f7d3b2 180deg 270deg,#bbddcf 270deg)':PALETTES[key].body}"></button>`).join('');
const conversions={frost:100,grain:100,tumble:1000,thickness:1,bulge:100,elevation:1,light:1,transmission:100};
function displayValue(key,n) {return key==='frost'||key==='grain'||key==='transmission'||key==='bulge'?`${Math.round(n*100)}%`:key==='tumble'?`${(n*100).toFixed(1)}%`:key==='light'?`${Math.round(n)}°`:`${Math.round(n)} px`;}
function syncControls() {
  for(const [key,factor] of Object.entries(conversions)) {
    const input=$(`#${key}`);input.value=Math.round(recipe.settings[key]*factor);$(`#${key}-value`).textContent=displayValue(key,recipe.settings[key]);input.setAttribute('aria-valuetext',displayValue(key,recipe.settings[key]));
    input.style.setProperty('--progress',`${(input.value-input.min)/(input.max-input.min)*100}%`);
  }
  $('#palette-name').textContent=paletteNames[recipe.palette];
  $$('[data-palette-choice]').forEach(el=>{const active=el.dataset.paletteChoice===recipe.palette;el.classList.toggle('selected',active);el.setAttribute('aria-pressed',String(active));});
  $$('[data-preset]').forEach(el=>{const preset=PRESETS[el.dataset.preset];const active=recipe.frostEnabled&&Object.entries(preset).every(([key,n])=>Math.abs(recipe.settings[key]-n)<.00001);el.classList.toggle('selected',active);el.setAttribute('aria-pressed',String(active));});
  $('#seed-label').textContent=`Shape seed ${recipe.settings.seed}`;
  $('#frost-switch').setAttribute('aria-checked',String(recipe.frostEnabled));
  $('#compare').disabled=!pinned;
  $('#pin-description').textContent=pinned?'A direction is pinned. Keep exploring.':'Keep a version while you explore.';
  $('#pin').querySelector('span:last-child').textContent=pinned?'Update pinned direction':'Pin this direction';
}
function redraw() {
  instances.forEach((renderer,el)=>renderer.update(materialSettings(recipe,Number(el.dataset.seed)||0),recipe.palette==='mix'?(el.dataset.palette||'lagoon'):recipe.palette));
  syncControls();persist();
}
function scheduleRedraw() {if(queued)return;queued=true;requestAnimationFrame(()=>{queued=false;redraw();});}
function setPalette(palette){recipe.palette=palette;scheduleRedraw();}
for(const [key,factor] of Object.entries(conversions)) $(`#${key}`).addEventListener('input',event=>{recipe.settings[key]=Number(event.target.value)/factor;if(key==='frost'||key==='grain')recipe.frostEnabled=true;scheduleRedraw();});
$$('[data-palette-choice]').forEach(el=>el.addEventListener('click',()=>setPalette(el.dataset.paletteChoice)));
$$('[data-select-palette]').forEach(el=>el.addEventListener('click',()=>setPalette(el.dataset.selectPalette)));
$$('[data-preset]').forEach(el=>el.addEventListener('click',()=>{recipe=applyPreset(recipe,el.dataset.preset);scheduleRedraw();}));
function reset(){recipe=normalizeRecipe();scheduleRedraw();showToast('Back to the original feel');}
$('#reset').addEventListener('click',reset);
function shuffle(){recipe.settings.seed=(recipe.settings.seed+7919)%2147483647;scheduleRedraw();showToast('Same material. A new set of shapes.');}
$('#shuffle').addEventListener('click',shuffle);
$('#frost-switch').addEventListener('click',()=>{recipe.frostEnabled=!recipe.frostEnabled;scheduleRedraw();});

function setScene(value){scene=value;$('#preview-shell').classList.toggle('dark-scene',value==='dark');$$('[data-scene]').forEach(el=>{const on=el.dataset.scene===value;el.classList.toggle('selected',on);el.setAttribute('aria-pressed',String(on));});persist();}
$$('[data-scene]').forEach(el=>el.addEventListener('click',()=>setScene(el.dataset.scene)));
function selectView(view){$$('[data-view]').forEach(el=>{const active=el.dataset.view===view;el.classList.toggle('active',active);el.setAttribute('aria-selected',String(active));el.tabIndex=active?0:-1;});$$('.preview-panel').forEach(el=>el.hidden=el.id!==`panel-${view}`);}
$$('[data-view]').forEach((el,index,tabs)=>{el.addEventListener('click',()=>selectView(el.dataset.view));el.addEventListener('keydown',event=>{if(!['ArrowRight','ArrowLeft','Home','End'].includes(event.key))return;event.preventDefault();const next=event.key==='Home'?0:event.key==='End'?tabs.length-1:(index+(event.key==='ArrowRight'?1:-1)+tabs.length)%tabs.length;selectView(tabs[next].dataset.view);tabs[next].focus();});});
$$('.segmented button').forEach(el=>el.addEventListener('click',()=>{$$('.segmented button').forEach(button=>{const active=button===el;button.classList.toggle('chosen',active);button.setAttribute('aria-pressed',String(active));});showToast(`${el.textContent} selected`);}));

async function copyText(text,success){try{await navigator.clipboard.writeText(text);showToast(success);}catch{const textarea=document.createElement('textarea');textarea.value=text;textarea.style.cssText='position:fixed;left:-9999px;top:0';document.body.append(textarea);textarea.select();const ok=document.execCommand('copy');textarea.remove();showToast(ok?success:'Copy unavailable in this browser. Use Download recipe.');}}
function copyRecipe(){copyText(JSON.stringify(exportRecipe(recipe),null,2),'Material recipe copied');}
$('#copy-config').addEventListener('click',copyRecipe);
$('#download-recipe').addEventListener('click',()=>{const url=URL.createObjectURL(new Blob([JSON.stringify(exportRecipe(recipe),null,2)],{type:'application/json'}));const link=document.createElement('a');link.href=url;link.download='sea-glass-recipe.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);showToast('Material recipe downloaded');});
$('#pin').addEventListener('click',()=>{pinned=normalizeRecipe(structuredClone(recipe));syncControls();persist();showToast('Direction pinned. Try something different.');});
function comparisonCard(which,r){return `<article class="comparison-card"><h3>${which==='pinned'?'Your pinned direction':'Your current direction'}</h3><div class="glass" data-shape="rounded" data-palette="lagoon" data-seed="33">Compose</div><div class="recipe-stats"><span>Frost<strong>${r.frostEnabled?displayValue('frost',r.settings.frost):'Off'}</strong></span><span>Tumble<strong>${displayValue('tumble',r.settings.tumble)}</strong></span><span>Thickness<strong>${displayValue('thickness',r.settings.thickness)}</strong></span><span>Color<strong>${paletteNames[r.palette]}</strong></span><span>Transmission<strong>${displayValue('transmission',r.settings.transmission)}</strong></span><span>Bulge<strong>${displayValue('bulge',r.settings.bulge)}</strong></span></div><button data-keep="${which}">${which==='pinned'?'Use pinned direction':'Keep current direction'}</button></article>`;}
$('#compare').addEventListener('click',()=>{if(!pinned)return;compareInstances.forEach(i=>i.destroy());$('#comparison-grid').innerHTML=comparisonCard('pinned',pinned)+comparisonCard('current',recipe);const cards=$$('.comparison-card');[pinned,recipe].forEach((r,index)=>{const el=cards[index].querySelector('.glass');const settings=materialSettings({...r,settings:{...r.settings,seed:pinned.settings.seed}},33);compareInstances.push(mountGlass(el,{shape:'rounded',palette:r.palette==='mix'?'lagoon':r.palette,settings}));});$$('[data-keep]').forEach(el=>el.addEventListener('click',()=>{if(el.dataset.keep==='pinned'){recipe=normalizeRecipe(structuredClone(pinned));redraw();}$('#comparison').close();showToast('Direction selected');}));$('#comparison').showModal();});
$('#close-comparison').addEventListener('click',()=>$('#comparison').close());
$('#comparison').addEventListener('click',event=>{if(event.target!==$('#comparison'))return;const rect=$('#comparison').getBoundingClientRect();if(event.clientX<rect.left||event.clientX>rect.right||event.clientY<rect.top||event.clientY>rect.bottom)$('#comparison').close();});

$('#more-actions').addEventListener('click',()=>{const open=$('#toolbar-menu').hidden;$('#toolbar-menu').hidden=!open;$('#more-actions').setAttribute('aria-expanded',String(open));});
$('#menu-copy').addEventListener('click',()=>{copyRecipe();$('#toolbar-menu').hidden=true;$('#more-actions').setAttribute('aria-expanded','false');});
$('#menu-reset').addEventListener('click',()=>{reset();$('#toolbar-menu').hidden=true;$('#more-actions').setAttribute('aria-expanded','false');});
document.addEventListener('keydown',event=>{if(event.key==='Escape'){$('#toolbar-menu').hidden=true;$('#more-actions').setAttribute('aria-expanded','false');}});
document.addEventListener('click',event=>{if(!event.target.closest('#toolbar-menu,#more-actions')){$('#toolbar-menu').hidden=true;$('#more-actions').setAttribute('aria-expanded','false');}});
$$('[data-action]').forEach(el=>{if(el.id==='download-recipe')return;el.addEventListener('click',()=>{const icon=el.querySelector('[data-icon]')?.dataset.icon;if(icon==='share'){copyText(location.href,'Playground link copied');}else if(el.classList.contains('add-button'))shuffle();else if(icon==='pencil'){selectView('context');showToast('Try the material in a notes interface');}else if(el.classList.contains('note-save')){showToast('Save note · press interaction preview');}else{showToast(`${el.getAttribute('aria-label')||el.textContent.trim()} · interaction preview`);}});});
let savedMoment=false;
$('#save-button').addEventListener('click',()=>{savedMoment=!savedMoment;$('#save-button').setAttribute('aria-pressed',String(savedMoment));$('#save-button').querySelector('[data-icon]').dataset.icon=savedMoment?'check':'bookmark';$('#save-button').querySelector('span:last-child').textContent=savedMoment?'Moment saved':'Save a moment';hydrateIcons($('#save-button'));});
$('#tag-button').addEventListener('click',()=>{const selected=$('#tag-button').getAttribute('aria-pressed')==='true';$('#tag-button').setAttribute('aria-pressed',String(!selected));$('#tag-button').style.opacity=selected?'.65':'1';});
setScene(scene);redraw();
