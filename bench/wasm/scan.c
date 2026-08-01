// A CBOR skeleton scanner: walks the structure and counts items, doing no
// value construction at all. This is the upper bound on what a WASM decoder
// could take off the JS "scan" bucket -- it is the scan and nothing else.
typedef unsigned char u8;
typedef unsigned int  u32;
typedef unsigned long long u64;

static u8 *B; static u32 P, LEN;

static u64 arg(u32 info) {
  if (info < 24) return info;
  if (info == 24) return B[P++];
  if (info == 25) { u64 v = ((u64)B[P] << 8) | B[P+1]; P += 2; return v; }
  if (info == 26) { u64 v = ((u64)B[P] << 24) | ((u64)B[P+1] << 16) | ((u64)B[P+2] << 8) | B[P+3]; P += 4; return v; }
  if (info == 27) { u64 v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | B[P+i]; P += 8; return v; }
  return 0;
}

// Returns the number of items walked.
static u32 walk(void) {
  u8 h = B[P++];
  u32 major = h >> 5, info = h & 0x1F;
  u64 n;
  switch (major) {
    case 0: case 1: arg(info); return 1;
    case 2: case 3: n = arg(info); P += (u32)n; return 1;
    case 4: { n = arg(info); u32 c = 1; for (u64 i = 0; i < n; i++) c += walk(); return c; }
    case 5: { n = arg(info); u32 c = 1; for (u64 i = 0; i < n; i++) { c += walk(); c += walk(); } return c; }
    case 6: arg(info); return 1 + walk();
    default: if (info == 25) P += 2; else if (info == 26) P += 4;
             else if (info == 27) P += 8; else if (info == 24) P += 1;
             return 1;
  }
}

u32 scan(u8 *buf, u32 len) { B = buf; P = 0; LEN = len; return walk(); }
static u8 heap[1 << 20];
u8 *heap_ptr(void) { return heap; }
