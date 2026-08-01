// Does a WASM scanner pay for itself? See bench/wasm/README.md.
//
// scan.c walks the CBOR structure and counts items, constructing nothing. That
// is the MOST a WASM module could take off the JS decoder, because everything
// else it would produce -- strings, Keywords, PersistentArrayMaps -- has to be
// built on the JS side regardless.
const fs = require("fs");
const plain = new Uint8Array(fs.readFileSync("./target/wasm/plain.cbor"));
const shaped = new Uint8Array(fs.readFileSync("./target/wasm/shaped.cbor"));

// The same skeleton walk in JS as scan.c does in WASM: structure only, no
// value construction. This isolates the "scan" bucket and nothing else.
function jsScan(B) {
  let P = 0;
  function arg(info) {
    if (info < 24) return info;
    if (info === 24) return B[P++];
    if (info === 25) { const v = (B[P] << 8) | B[P+1]; P += 2; return v; }
    if (info === 26) { const v = (B[P]*16777216) + (B[P+1]<<16) + (B[P+2]<<8) + B[P+3]; P += 4; return v; }
    if (info === 27) { let v = 0; for (let i=0;i<8;i++) v = v*256 + B[P+i]; P += 8; return v; }
    return 0;
  }
  function walk() {
    const h = B[P++], major = h >> 5, info = h & 0x1F;
    let n, c;
    switch (major) {
      case 0: case 1: arg(info); return 1;
      case 2: case 3: n = arg(info); P += n; return 1;
      case 4: n = arg(info); c = 1; for (let i=0;i<n;i++) c += walk(); return c;
      case 5: n = arg(info); c = 1; for (let i=0;i<n;i++) { c += walk(); c += walk(); } return c;
      case 6: arg(info); return 1 + walk();
      default:
        if (info === 25) P += 2; else if (info === 26) P += 4;
        else if (info === 27) P += 8; else if (info === 24) P += 1;
        return 1;
    }
  }
  P = 0; return walk();
}

function bench(nm, f) {
  for (let i = 0; i < 5000; i++) f();
  let it = 200;
  for (;;) {
    const t0 = process.hrtime.bigint();
    for (let i = 0; i < it; i++) f();
    const dt = Number(process.hrtime.bigint() - t0) / 1e6;
    if (dt < 250) { it *= 4; continue; }
    console.log("   ", nm.padEnd(40), (dt * 1e6 / it).toFixed(0).padStart(8), "ns");
    return dt * 1e6 / it;
  }
}

(async () => {
  const mod = await WebAssembly.instantiate(fs.readFileSync("./target/wasm/scan.wasm"), {});
  const e = mod.instance.exports;
  const mem = new Uint8Array(e.memory.buffer);
  const base = e.heap_ptr();
  mem.set(plain, base);                       // copy once, outside the loop

  console.log("CBOR skeleton scan of datom-maps-200 (9952 bytes), node", process.version, "\n");
  const j = bench("JS skeleton scan", () => jsScan(plain));
  const w = bench("WASM skeleton scan (buffer resident)", () => e.scan(base, plain.length));
  // What it costs if the bytes arrive in JS and must be copied in each time,
  // which is the real situation for a decoder handed a fresh Uint8Array.
  const wc = bench("WASM + copy buffer in each call", () => {
    mem.set(plain, base);
    return e.scan(base, plain.length);
  });
  console.log("\n    WASM is", (j/w).toFixed(2) + "x the JS scan (resident),",
              (j/wc).toFixed(2) + "x (with copy)");
  console.log("    sanity: JS and WASM agree on the item count?",
              jsScan(plain) === e.scan(base, plain.length), "(" + jsScan(plain) + " items)");

  console.log("\nsame, shaped encoding (4982 bytes):\n");
  mem.set(shaped, base);
  const j2 = bench("JS skeleton scan", () => jsScan(shaped));
  const w2 = bench("WASM skeleton scan", () => e.scan(base, shaped.length));
  console.log("\n    WASM is", (j2/w2).toFixed(2) + "x the JS scan");

  console.log("\n--- what that is worth, against measured full decodes ---");
  const FULL_PLAIN = 170279, FULL_SHAPED = 45127;   // doc/PERFORMANCE.md
  console.log("    generic: skeleton scan is", (100*j/FULL_PLAIN).toFixed(1) + "% of the",
              FULL_PLAIN + " ns decode;");
  console.log("             moving ALL of it to WASM saves", (j-w).toFixed(0), "ns =",
              (100*(j-w)/FULL_PLAIN).toFixed(1) + "%");
  console.log("    shaped:  skeleton scan is", (100*j2/FULL_SHAPED).toFixed(1) + "% of the",
              FULL_SHAPED + " ns decode;");
  console.log("             moving ALL of it to WASM saves", (j2-w2).toFixed(0), "ns =",
              (100*(j2-w2)/FULL_SHAPED).toFixed(1) + "%");
})();
