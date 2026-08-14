// Generates zygote-192.png and zygote-512.png with zero dependencies
// (Node zlib + a minimal PNG encoder). Dark rounded background with a blue
// zygote mark, matching public/icons/zygote.svg.
import { deflateSync } from 'node:zlib';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const outDir = join(__dirname, '..', 'public', 'icons');

const BG = [10, 10, 11]; // #0A0A0B
const BLUE = [59, 130, 246]; // #3B82F6

function crc32(buf) {
  let c = ~0;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return ~c >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const body = Buffer.concat([typeBuf, data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

function makePng(size) {
  const px = Buffer.alloc(size * size * 4);
  const cx = size / 2;
  const cy = size / 2;
  const bgR = size * 0.22; // rounded-rect corner radius
  const headR = size * 0.30;
  const inset = size * 0.20;

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = (y * size + x) * 4;
      // rounded rect background
      const rr = roundedRect(x + 0.5, y + 0.5, size, size, bgR);
      let r = BG[0], g = BG[1], b = BG[2], a = 255;
      if (!rr) { r = g = b = 0; a = 0; }
      else {
        // head circle
        const dHead = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
        if (dHead <= headR) { r = BLUE[0]; g = BLUE[1]; b = BLUE[2]; }
        else {
          // inner ring cut
          const innerR = headR * 0.62;
          if (dHead <= innerR) { r = BG[0]; g = BG[1]; b = BG[2]; }
        }
        // tail: a curved blob toward the right, above center
        const tx = x + 0.5, ty = y + 0.5;
        const tdx = tx - (cx + headR * 0.45);
        const tdy = ty - (cy - size * 0.10);
        if (tdx > 0 && Math.hypot(tdx * 0.5, tdy) < size * 0.16 && ty > cy - size * 0.30 && ty < cy + size * 0.10) {
          r = BLUE[0]; g = BLUE[1]; b = BLUE[2];
        }
        // soft edge: 1px anti-alias toward bg
        if (rr < 0.75) {
          const blend = 1 - Math.max(0, Math.min(1, rr));
          r = Math.round(r + (BG[0] - r) * blend);
          g = Math.round(g + (BG[1] - g) * blend);
          b = Math.round(b + (BG[2] - b) * blend);
        }
      }
      px[i] = r; px[i + 1] = g; px[i + 2] = b; px[i + 3] = a;
    }
  }

  const raw = Buffer.alloc((size * 4 + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0; // filter: none
    px.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  }
  const idat = deflateSync(raw, { level: 9 });

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 6;  // color type RGBA
  ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;

  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]);
}

function roundedRect(x, y, w, h, r) {
  const m = w / 2;
  const dx = Math.abs(x - m) - (m - r);
  const dy = Math.abs(y - m) - (m - r);
  const outside = Math.max(dx, dy);
  if (outside > 0) {
    const dist = Math.hypot(Math.max(dx, 0), Math.max(dy, 0)) + Math.min(Math.max(dx, dy), 0) - r;
    return dist;
  }
  return -1;
}

mkdirSync(outDir, { recursive: true });
for (const s of [192, 512]) {
  const file = join(outDir, `zygote-${s}.png`);
  writeFileSync(file, makePng(s));
  console.log('wrote', file);
}
