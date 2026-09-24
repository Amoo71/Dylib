export const SPECIAL_SHAPES={
 'slope-1x1':{kind:'slope',w:1,h:1},'slope-1x2':{kind:'slope',w:2,h:1},'slope-1x3':{kind:'slope',w:3,h:1},'slope-2x2':{kind:'slope',w:2,h:2},'slope-2x3':{kind:'slope',w:3,h:2},'slope-2x4':{kind:'slope',w:4,h:2},
 'window-1x2':{kind:'window',w:2,h:1},'window-1x4':{kind:'window',w:4,h:1},'window-2x2':{kind:'window',w:2,h:2},'window-2x4':{kind:'window',w:4,h:2},'door-1x4':{kind:'door',w:4,h:1},
 'wheel-small':{kind:'wheel',w:1,h:1},'wheel-medium':{kind:'wheel',w:1,h:1},'wheel-large':{kind:'wheel',w:1,h:1},'wheel-with-axle':{kind:'wheel',w:1,h:1},
 'leaf':{kind:'leaf',w:1,h:1},'leaf-branch':{kind:'leaf',w:1,h:1},'flower':{kind:'leaf',w:1,h:1},'stem':{kind:'stem',w:1,h:1},'bush':{kind:'leaf',w:2,h:1}
};
export function shape(id,rotation=0){const s=SPECIAL_SHAPES[id];if(!s)return null;return rotation%2?{...s,w:s.h,h:s.w}:s}
