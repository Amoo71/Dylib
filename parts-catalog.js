// Expanded LEGO-style catalog for the builder. Quantities are user-editable.
export const PART_CATEGORIES={
 bricks:['1x1','1x2','1x3','1x4','1x6','1x8','2x2','2x3','2x4','2x6','2x8'],
 plates:['plate-1x1','plate-1x2','plate-1x3','plate-1x4','plate-1x6','plate-2x2','plate-2x4','plate-2x6','plate-2x8'],
 slopes:['slope-1x1','slope-1x2','slope-1x3','slope-2x2','slope-2x3','slope-2x4'],
 windows:['window-1x2','window-1x4','window-2x2','window-2x4','door-1x4'],
 wheels:['wheel-small','wheel-medium','wheel-large','wheel-with-axle','axle-2','axle-4','axle-6'],
 plants:['leaf','leaf-branch','flower','stem','bush'],
 round:['round-1x1','round-2x2','cone-1x1','cylinder-2x2'],
 special:['hinge','clip','bar','bracket','tile-1x1','tile-1x2','tile-2x2']
};
export const PARTS={};for(const [category,ids] of Object.entries(PART_CATEGORIES))for(const id of ids)PARTS[id]={id,category,defaultCount:2};
export function partInfo(id){return PARTS[id]||null}
