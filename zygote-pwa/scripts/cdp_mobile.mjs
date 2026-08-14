// CDP: emulate a real phone viewport (360 CSS x 2 DPR = 720x1600 like the
// SM-M176B), read geometry, and capture a screenshot to a PNG file.
import { writeFileSync } from 'node:fs';
const port = process.env.CDP_PORT || '9222';
const URL = 'http://localhost:4173/';
const W = 360, H = 800, DPR = 2;
async function main() {
  const ver = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
  const ws = new WebSocket(ver.webSocketDebuggerUrl);
  let id = 0; const pending = new Map();
  const send = (method, params = {}, sessionId) =>
    new Promise((resolve) => { const i = ++id; pending.set(i, resolve);
      ws.send(JSON.stringify({ id: i, method, params, ...(sessionId ? { sessionId } : {}) })); });
  ws.onmessage = (e) => { const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); } };
  await new Promise((r) => (ws.onopen = r));

  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Runtime.enable', {}, sessionId);
  await send('Page.enable', {}, sessionId);
  await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: DPR, mobile: true }, sessionId);
  await send('Page.navigate', { url: URL }, sessionId);
  await new Promise((r) => setTimeout(r, 2500));

  const geo = await send('Runtime.evaluate', {
    expression: `(() => {
      const g=(s)=>{const el=document.querySelector(s);if(!el)return null;const r=el.getBoundingClientRect();const c=getComputedStyle(el);return{ow:el.offsetWidth,left:Math.round(r.left),right:Math.round(r.right),fw:c.flexWrap};};
      const o={innerW:window.innerWidth,dpr:window.devicePixelRatio,docClientW:document.documentElement.clientWidth,docScrollW:document.documentElement.scrollWidth};
      for(const s of ['.composer','.composer-tools','.tools','.trailing','.todos','.statusbar','.offline .sub']) o[s]=g(s);
      return o; })()`,
    returnByValue: true,
  }, sessionId);
  console.log('GEOMETRY:', JSON.stringify(geo.result.value));

  const shot = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
  writeFileSync(process.argv[2] || 'mobile-true.png', Buffer.from(shot.data, 'base64'));
  console.log('saved screenshot ->', process.argv[2] || 'mobile-true.png');
  ws.close();
}
main().catch((e) => { console.error('ERR', e.message); process.exit(1); });
