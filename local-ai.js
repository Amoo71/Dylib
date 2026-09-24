import { CreateMLCEngine } from 'https://esm.run/@mlc-ai/web-llm';

const MODEL = 'Llama-3.2-1B-Instruct-q4f16_1-MLC';
let enginePromise;

export function localAIStatus(cb){
  enginePromise ||= CreateMLCEngine(MODEL, { initProgressCallback: p => cb?.(p) });
  return enginePromise;
}

export async function generateLocalPlan(request, stock, progress){
  const engine = await localAIStatus(progress);
  const available = Object.entries(stock).filter(([,n])=>Number(n)>0).map(([s,n])=>`${s} x${n}`).join(', ');
  const system = `You are a LEGO build planner. Return ONLY JSON, no markdown. Schema: {"name":"...","steps":[{"size":"1x2","x":0,"y":0,"z":0,"rotation":0,"color":"#e11d2e"}]}. Use ONLY available sizes and never exceed quantities. Coordinates are studs. Make a small build that is physically plausible and compact.`;
  const user = `Build request: ${request}\nAvailable: ${available}`;
  const out = await engine.chat.completions.create({messages:[{role:'system',content:system},{role:'user',content:user}],temperature:0.2,max_tokens:1200});
  const text = out.choices?.[0]?.message?.content || '';
  return JSON.parse(text.replace(/^```json\s*|\s*```$/g,'').trim());
}
