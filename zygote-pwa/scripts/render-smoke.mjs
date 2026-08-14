// SSR smoke test: render <App/> to HTML to prove the component tree renders
// without crashing and contains the expected UI strings. Run with node from
// the project root. (Uses vite's ssrLoadModule so TSX imports resolve.)
import { createServer } from 'vite';
import { renderToString } from 'react-dom/server';
import React from 'react';

const server = await createServer({ server: { middlewareMode: true }, appType: 'custom' });
try {
  const mod = await server.ssrLoadModule('/src/App.tsx');
  const App = mod.default;
  const html = renderToString(React.createElement(App));
  const checks = {
    'rail nav': html.includes('class="rail"'),
    'logo': html.includes('zygote'),
    'title "Greetings from the user"': html.includes('Greetings from the user'),
    'Standard mode chip': html.includes('Standard mode'),
    'Session log button': html.includes('Session log'),
    'Chat tab': html.includes('>Chat<'),
    'Trajectory tab': html.includes('>Trajectory<'),
    'offline state (no backend)': html.includes('Harness offline'),
    '127.0.0.1:8787 ref': html.includes('127.0.0.1:8787'),
    'To-dos panel': html.includes('To-dos'),
    'composer placeholder': html.includes('Message the agent'),
    'Full access': html.includes('Full access'),
    'LFM2.5-2.6B model': html.includes('LFM2.5-2.6B'),
    'local tag': html.includes('local'),
    'status bar turns': html.includes('turns'),
    'status bar cache': html.includes('Cache hit'),
  };
  let fail = 0;
  for (const [k, v] of Object.entries(checks)) {
    console.log(`${v ? 'PASS' : 'FAIL'}  ${k}`);
    if (!v) fail++;
  }
  console.log(fail === 0 ? '\nALL CHECKS PASSED' : `\n${fail} CHECK(S) FAILED`);
  process.exit(fail === 0 ? 0 : 1);
} finally {
  await server.close();
}
