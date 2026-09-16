/**
 * Deterministic AIStudy app icon generator (no third-party deps).
 *
 * Emits build/icon.png (512x512 RGBA) and build/icon.ico (a 256x256
 * ICO container wrapping a PNG — supported on Windows Vista+).
 *
 * Design: rounded-square tile with a vertical indigo→blue gradient and
 * a geometric white "A" mark (two legs + crossbar, distance-field
 * strokes). Re-run with: npm run icon:generate
 */

import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const BUILD_DIR = join(ROOT, 'build');

/* ----------------------------- PNG encoding ----------------------------- */

function crc32Table() {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[n] = c >>> 0;
  }
  return table;
}
const CRC_TABLE = crc32Table();

function crc32(buf) {
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc = CRC_TABLE[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const typeBuf = Buffer.from(type, 'ascii');
  const lenBuf = Buffer.alloc(4);
  lenBuf.writeUInt32BE(data.length, 0);
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([lenBuf, typeBuf, data, crcBuf]);
}

function encodePng(width, height, rgba) {
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // color type RGBA
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;
  // raw scanlines with filter byte 0
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const idat = deflateSync(raw, { level: 9 });
  return Buffer.concat([
    sig,
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', idat),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

/* ------------------------------- Painting ------------------------------- */

function lerp(a, b, t) {
  return Math.round(a + (b - a) * t);
}

/** Distance from point P to segment AB. */
function distToSegment(px, py, ax, ay, bx, by) {
  const dx = bx - ax;
  const dy = by - ay;
  const lenSq = dx * dx + dy * dy;
  let t = lenSq === 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / lenSq;
  t = Math.max(0, Math.min(1, t));
  const cx = ax + t * dx;
  const cy = ay + t * dy;
  return Math.hypot(px - cx, py - cy);
}

function inRoundedRect(x, y, size, radius) {
  const r = radius;
  if (x >= r && x <= size - r) return true;
  if (y >= r && y <= size - r) return true;
  const cx = x < r ? r : size - r;
  const cy = y < r ? r : size - r;
  return Math.hypot(x - cx, y - cy) <= r;
}

function renderIcon(size) {
  const rgba = Buffer.alloc(size * size * 4);
  // Gradient stops (top indigo -> bottom blue), matching --color-primary.
  const top = [79, 107, 237];
  const bottom = [96, 165, 250];
  const radius = size * 0.1875; // 96/512

  // "A" geometry (normalized to 512 then scaled)
  const s = size / 512;
  const legs = [
    [156, 382, 256, 128], // left leg: bottom-left -> apex
    [256, 128, 356, 382], // right leg: apex -> bottom-right
  ];
  const legHalf = 22 * s;
  const crossbar = { y: 300, x1: 196, x2: 316, half: 17 * s };

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const idx = (y * size + x) * 4;
      if (!inRoundedRect(x, y, size, radius)) {
        rgba[idx + 3] = 0; // transparent outside tile
        continue;
      }
      const t = y / size;
      let rr = lerp(top[0], bottom[0], t);
      let gg = lerp(top[1], bottom[1], t);
      let bb = lerp(top[2], bottom[2], t);

      // White A mark: distance to either leg within stroke, or inside
      // the horizontal crossbar band.
      const px = x / s;
      const py = y / s;
      const onLeg =
        distToSegment(px, py, ...legs[0]) <= legHalf ||
        distToSegment(px, py, ...legs[1]) <= legHalf;
      const onBar =
        Math.abs(py - crossbar.y) <= crossbar.half &&
        px >= crossbar.x1 &&
        px <= crossbar.x2;
      if (onLeg || onBar) {
        rr = gg = bb = 255;
      }

      rgba[idx] = rr;
      rgba[idx + 1] = gg;
      rgba[idx + 2] = bb;
      rgba[idx + 3] = 255;
    }
  }
  return rgba;
}

/** Wrap a PNG buffer into a single-image ICO container. */
function pngToIco(png, size) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0); // reserved
  header.writeUInt16LE(1, 2); // type: icon
  header.writeUInt16LE(1, 4); // one image

  const entry = Buffer.alloc(16);
  entry[0] = size >= 256 ? 0 : size; // width (0 => 256)
  entry[1] = size >= 256 ? 0 : size; // height
  entry[2] = 0; // palette
  entry[3] = 0; // reserved
  entry.writeUInt16LE(1, 4); // color planes
  entry.writeUInt16LE(32, 6); // bits per pixel
  entry.writeUInt32LE(png.length, 8); // size of image data
  entry.writeUInt32LE(6 + 16, 12); // offset to image data

  return Buffer.concat([header, entry, png]);
}

/* --------------------------------- Main --------------------------------- */

mkdirSync(BUILD_DIR, { recursive: true });

const big = renderIcon(512);
const png512 = encodePng(512, 512, big);
writeFileSync(join(BUILD_DIR, 'icon.png'), png512);

// 256 version for the ICO (downscale nearest box average).
function downscale(src, from, to) {
  const out = Buffer.alloc(to * to * 4);
  const block = from / to;
  for (let y = 0; y < to; y++) {
    for (let x = 0; x < to; x++) {
      let r = 0,
        g = 0,
        b = 0,
        a = 0,
        n = 0;
      for (let sy = 0; sy < block; sy++) {
        for (let sx = 0; sx < block; sx++) {
          const si = ((y * block + sy) * from + (x * block + sx)) * 4;
          r += src[si];
          g += src[si + 1];
          b += src[si + 2];
          a += src[si + 3];
          n++;
        }
      }
      const oi = (y * to + x) * 4;
      out[oi] = Math.round(r / n);
      out[oi + 1] = Math.round(g / n);
      out[oi + 2] = Math.round(b / n);
      out[oi + 3] = Math.round(a / n);
    }
  }
  return out;
}
const png256 = encodePng(256, 256, downscale(big, 512, 256));
writeFileSync(join(BUILD_DIR, 'icon.ico'), pngToIco(png256, 256));

console.log('generated build/icon.png (512) and build/icon.ico (256)');
