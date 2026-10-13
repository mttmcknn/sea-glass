import test from 'node:test';
import assert from 'node:assert/strict';
import {buildContour,contourSegments} from '../dist/sea-glass.mjs';

const sizes = [[450,82],[270,88],[76,76],[25,25],[218,76],[72,70],[95,95],[115,76],[145,42],[176,46],[185,46]];
const seeds = [0,11,25,33,42,61,72,86,155,2147483000];
const shapes = ['rounded','capsule','disc'];

function derivatives(segment,t) {
  const p = [segment.start,segment.control1,segment.control2,segment.end];
  return ['x','y'].map(axis=>({
    first:3*((1-t)**2*(p[1][axis]-p[0][axis])+2*t*(1-t)*(p[2][axis]-p[1][axis])+t*t*(p[3][axis]-p[2][axis])),
    second:6*((1-t)*(p[2][axis]-2*p[1][axis]+p[0][axis])+t*(p[3][axis]-2*p[2][axis]+p[1][axis])),
  }));
}

function curvature(segment,t) {
  const [x,y] = derivatives(segment,t);
  const speed = Math.hypot(x.first,y.first);
  assert.ok(speed>0,'a worn outline must not have a cusp or stationary segment');
  return (x.first*y.second-y.first*x.second)/speed**3;
}

test('worn outline has continuous tangents and curvature at every seam, including closure',()=>{
  for(const [w,h] of sizes)for(const shape of shapes)for(const seed of seeds)for(const tumble of [0,.065])for(const count of [64,160,256]){
    const segments = contourSegments(buildContour(w,h,shape,tumble,seed,count));
    for(let i=0;i<segments.length;i++){
      const current = segments[i],next = segments[(i+1)%segments.length];
      const end = derivatives(current,1),start = derivatives(next,0);
      for(const [axis,index] of [['x',0],['y',1]]){
        assert.ok(Math.abs(current.end[axis]-next.start[axis])<1e-9,'the outline must stay closed');
        assert.ok(Math.abs(end[index].first-start[index].first)<1e-9,'a seam must not change tangent');
        assert.ok(Math.abs(end[index].second-start[index].second)<1e-9,'a seam must not change curvature');
      }
    }
  }
});

test('tumbling retains broad worn edges without tiny-radius hooks at old line/arc joins',()=>{
  for(const [w,h] of sizes)for(const shape of shapes){
    // A disc intentionally stretched into a long ellipse has tight ends; the
    // playground uses near-circular discs and long rounded/capsule controls.
    if(shape==='disc'&&Math.max(w,h)/Math.min(w,h)>1.5)continue;
    for(const seed of seeds)for(const tumble of [0,.025,.065])for(const count of [64,160,256]){
      const segments = contourSegments(buildContour(w,h,shape,tumble,seed,count));
      for(const segment of segments)for(let step=0;step<=12;step++){
        const normalizedCurvature = curvature(segment,step/12)*Math.min(w,h);
        // Local radius must exceed one quarter of the short side. The old
        // interpolating joins failed this even with tumbling disabled.
        assert.ok(Math.abs(normalizedCurvature)<4,`${shape} ${w}×${h}, seed ${seed}, tumble ${tumble}: local edge too tight (${normalizedCurvature})`);
      }
    }
  }
});

test('untumbled controls stay convex instead of curling inward beside their rounded corners',()=>{
  for(const [w,h] of sizes)for(const shape of ['rounded','capsule']){
    const segments = contourSegments(buildContour(w,h,shape,0,42));
    for(const segment of segments)for(let step=0;step<=12;step++){
      const normalizedCurvature = curvature(segment,step/12)*Math.min(w,h);
      assert.ok(normalizedCurvature>=-1e-9,'an untumbled corner must not introduce an inward hook');
      assert.ok(normalizedCurvature<2.65,'an untumbled piece must have generously rounded corners');
    }
  }
});
