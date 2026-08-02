import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Steelman for the FFM question: is a MemorySegment hot path faster than
 * byte[] + VarHandle for the access pattern a CBOR codec actually has --
 * BIG-ENDIAN, UNALIGNED scalar reads/writes interleaved with single bytes?
 *
 * Three backings, same work:
 *   A  byte[]              + byteArrayViewVarHandle   (what boring does)
 *   B  heap MemorySegment  (ofArray)                  (the 2024 measurement)
 *   C  native MemorySegment from an Arena             (what hako does)
 *
 * Method follows bench/ab.clj: alternate the variants in short bursts inside
 * one loop so all see the same machine conditions, take the min over rounds.
 * A checksum is printed so nothing is dead-code eliminated.
 *
 * Standalone -- no classpath, no deps. Needs JDK 22+ for the final FFM API:
 *
 *   javac -d target/ffmprobe bench/java/FfmProbe.java
 *   java --enable-native-access=ALL-UNNAMED -cp target/ffmprobe FfmProbe
 *
 * Run it on more than one JIT. The BE/LE gap it finds is a C2 property, and
 * GraalVM hides it -- see "Why the hot path is byte[]" in doc/PERFORMANCE.md.
 */
public class FfmProbe {

    static final VarHandle LONG_BE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    static final VarHandle INT_BE =
        MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    // Unaligned + big-endian: exactly what a CBOR head byte followed by an
    // 8-byte argument produces. Aligned layouts would flatter the segment.
    static final ValueLayout.OfLong  SEG_LONG =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    static final ValueLayout.OfInt   SEG_INT =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    static final int N = 1 << 16;      // bytes in the working buffer
    static final int STRIDE = 9;       // 1 header byte + 8 payload bytes
    static final int OPS = (N - 16) / STRIDE;

    static long sink;

    // ---- writers ------------------------------------------------------

    static void writeArray(byte[] b) {
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            b[off] = (byte) (0x1b);
            LONG_BE.set(b, off + 1, (long) i);
        }
    }

    static void writeSeg(MemorySegment s) {
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            s.set(ValueLayout.JAVA_BYTE, off, (byte) 0x1b);
            s.set(SEG_LONG, off + 1, (long) i);
        }
    }

    // ---- readers ------------------------------------------------------

    static long readArray(byte[] b) {
        long acc = 0;
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            acc += b[off];
            acc += (long) LONG_BE.get(b, off + 1);
        }
        return acc;
    }

    static long readSeg(MemorySegment s) {
        long acc = 0;
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            acc += s.get(ValueLayout.JAVA_BYTE, off);
            acc += s.get(SEG_LONG, off + 1);
        }
        return acc;
    }

    // ---- little-endian twins of the above (hako's wire order) ----------

    static final VarHandle LONG_LE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong SEG_LONG_LE =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    static void writeArrayLE(byte[] b) {
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            b[off] = (byte) 0x1b;
            LONG_LE.set(b, off + 1, (long) i);
        }
    }

    static void writeSegLE(MemorySegment s) {
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            s.set(ValueLayout.JAVA_BYTE, off, (byte) 0x1b);
            s.set(SEG_LONG_LE, off + 1, (long) i);
        }
    }

    static long readArrayLE(byte[] b) {
        long acc = 0;
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            acc += b[off];
            acc += (long) LONG_LE.get(b, off + 1);
        }
        return acc;
    }

    static long readSegLE(MemorySegment s) {
        long acc = 0;
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            acc += s.get(ValueLayout.JAVA_BYTE, off);
            acc += s.get(SEG_LONG_LE, off + 1);
        }
        return acc;
    }

    // ---- the workaround: native-order access + an explicit bswap -------
    //
    // If the BE penalty is the JIT declining to intrinsify a non-native
    // ValueLayout, then reading LE and calling Long.reverseBytes (itself a
    // bswap intrinsic) should recover full speed and still produce the
    // big-endian value CBOR needs. This is the experiment that decides
    // whether an mmap-backed reader is affordable.

    static void writeSegSwap(MemorySegment s) {
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            s.set(ValueLayout.JAVA_BYTE, off, (byte) 0x1b);
            s.set(SEG_LONG_LE, off + 1, Long.reverseBytes((long) i));
        }
    }

    static long readSegSwap(MemorySegment s) {
        long acc = 0;
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            acc += s.get(ValueLayout.JAVA_BYTE, off);
            acc += Long.reverseBytes(s.get(SEG_LONG_LE, off + 1));
        }
        return acc;
    }

    // ---- 4-byte variants: CBOR's most common multi-byte width ----------

    static void writeArray4(byte[] b) {
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            b[off] = (byte) 0x1a;
            INT_BE.set(b, off + 1, i);
        }
    }

    static void writeSeg4(MemorySegment s) {
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            s.set(ValueLayout.JAVA_BYTE, off, (byte) 0x1a);
            s.set(SEG_INT, off + 1, i);
        }
    }

    // ---- byte-at-a-time: the UTF-8 / bytestring copy path ---------------

    static void copyArray(byte[] src, byte[] dst) {
        System.arraycopy(src, 0, dst, 0, N);
    }

    static void copySegToArray(MemorySegment src, byte[] dst) {
        MemorySegment.copy(src, ValueLayout.JAVA_BYTE, 0, dst, 0, N);
    }

    // ---- byte-at-a-time: what a CBOR SCANNER actually does -------------
    //
    // The rows above read 8 bytes at a time. A CBOR head parser does not: it
    // reads one byte, branches on it, and walks. If segments lose here, a
    // segment-backed reader loses regardless of what the wide-load rows say,
    // so this is the row that decides whether ONE reader can serve byte[] and
    // mmap'ed files alike.

    static long scanArray(byte[] b) {
        long acc = 0;
        for (int i = 0; i < N; i++) acc += (b[i] & 0xFF);
        return acc;
    }

    static long scanSeg(MemorySegment s) {
        long acc = 0;
        for (int i = 0; i < N; i++) acc += (s.get(ValueLayout.JAVA_BYTE, i) & 0xFF);
        return acc;
    }

    // Data-dependent walk: each step's address depends on the previous byte,
    // defeating prefetch and unrolling the way a real head-parser does.
    static long walkArray(byte[] b) {
        long acc = 0;
        int p = 0;
        while (p < N - 8) { int v = b[p] & 0x07; acc += v; p += v + 1; }
        return acc;
    }

    static long walkSeg(MemorySegment s) {
        long acc = 0;
        int p = 0;
        while (p < N - 8) { int v = s.get(ValueLayout.JAVA_BYTE, p) & 0x07; acc += v; p += v + 1; }
        return acc;
    }

    // ---- ByteBuffer: the JDK-9 route to ONE implementation --------------
    //
    // MemorySegment would force a JDK 22+ baseline. ByteBuffer is the other
    // backing that abstracts over heap and mmap in a single implementation,
    // and it works on JDK 9. Two things make it a real candidate: absolute
    // get(int) needs no position bookkeeping, and BIG_ENDIAN is ByteBuffer's
    // DEFAULT order -- so it has no equivalent of the withOrder(BIG_ENDIAN)
    // cliff that costs MemorySegment 4.1x. Its limit is 2 GB per mapping.

    static long scanBuf(ByteBuffer b) {
        long acc = 0;
        for (int i = 0; i < N; i++) acc += (b.get(i) & 0xFF);
        return acc;
    }

    static long walkBuf(ByteBuffer b) {
        long acc = 0;
        int p = 0;
        while (p < N - 8) { int v = b.get(p) & 0x07; acc += v; p += v + 1; }
        return acc;
    }

    static long readBufLong(ByteBuffer b) {
        long acc = 0;
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            acc += b.get(off);
            acc += b.getLong(off + 1);
        }
        return acc;
    }

    static void writeBufLong(ByteBuffer b) {
        for (int i = 0, off = 0; i < OPS; i++, off += STRIDE) {
            b.put(off, (byte) 0x1b);
            b.putLong(off + 1, (long) i);
        }
    }

    // ---- harness -------------------------------------------------------

    interface Op { void run(); }

    /** Alternating bursts, min over rounds. Returns ns/op-of-`each`. */
    static double[] ab(Op a, Op b, int burst, int rounds, int perRun) {
        for (int i = 0; i < burst * 20; i++) { a.run(); b.run(); }
        long bestA = Long.MAX_VALUE, bestB = Long.MAX_VALUE;
        for (int r = 0; r < rounds; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < burst; i++) a.run();
            long t1 = System.nanoTime();
            for (int i = 0; i < burst; i++) b.run();
            long t2 = System.nanoTime();
            bestA = Math.min(bestA, t1 - t0);
            bestB = Math.min(bestB, t2 - t1);
        }
        double d = (double) burst * perRun;
        return new double[] { bestA / d, bestB / d };
    }

    static void row(String label, String na, String nb, double[] r) {
        double ratio = r[1] / r[0];
        String winner = ratio > 1.03 ? na : (ratio < 0.97 ? nb : "tie");
        System.out.printf("%-26s %10.3f %10.3f  %6.2fx  %s%n",
                          label, r[0], r[1], ratio, winner);
    }

    public static void main(String[] args) {
        byte[] arr = new byte[N];
        byte[] dst = new byte[N];
        MemorySegment heap = MemorySegment.ofArray(new byte[N]);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nat = arena.allocate(N, 8);

            System.out.println("JVM: " + System.getProperty("java.vm.name")
                               + " " + System.getProperty("java.version"));
            System.out.println("ns per scalar op (1 byte + 1 scalar), unaligned big-endian");
            System.out.printf("%-26s %10s %10s  %6s  %s%n",
                              "op", "byte[]", "segment", "ratio", "winner");
            System.out.println("-".repeat(70));

            row("write long  vs heap-seg", "byte[]", "heapseg",
                ab(() -> writeArray(arr), () -> writeSeg(heap), 200, 200, OPS));
            row("write long  vs native",   "byte[]", "nativeseg",
                ab(() -> writeArray(arr), () -> writeSeg(nat), 200, 200, OPS));
            row("read  long  vs heap-seg", "byte[]", "heapseg",
                ab(() -> { sink += readArray(arr); }, () -> { sink += readSeg(heap); }, 200, 200, OPS));
            row("read  long  vs native",   "byte[]", "nativeseg",
                ab(() -> { sink += readArray(arr); }, () -> { sink += readSeg(nat); }, 200, 200, OPS));
            row("write int   vs heap-seg", "byte[]", "heapseg",
                ab(() -> writeArray4(arr), () -> writeSeg4(heap), 200, 200, OPS));
            row("write int   vs native",   "byte[]", "nativeseg",
                ab(() -> writeArray4(arr), () -> writeSeg4(nat), 200, 200, OPS));

            // Is the native penalty just the confined arena's liveness /
            // thread check? A global-arena segment is never closed, so the
            // JIT has nothing to check. If THIS one is fast, the steelman
            // survives and the fix is "pick a different arena".
            MemorySegment glob = Arena.global().allocate(N, 8);
            MemorySegment shared;
            Arena sharedArena = Arena.ofShared();
            shared = sharedArena.allocate(N, 8);

            System.out.println();
            System.out.println("same, but varying the arena that owns the native segment");
            row("write long  vs global",  "byte[]", "globalseg",
                ab(() -> writeArray(arr), () -> writeSeg(glob), 200, 200, OPS));
            row("read  long  vs global",  "byte[]", "globalseg",
                ab(() -> { sink += readArray(arr); }, () -> { sink += readSeg(glob); }, 200, 200, OPS));
            row("write long  vs shared",  "byte[]", "sharedseg",
                ab(() -> writeArray(arr), () -> writeSeg(shared), 200, 200, OPS));
            row("read  long  vs shared",  "byte[]", "sharedseg",
                ab(() -> { sink += readArray(arr); }, () -> { sink += readSeg(shared); }, 200, 200, OPS));
            sharedArena.close();

            // hako writes LITTLE-endian (a free format choice); CBOR forces
            // boring into BIG-endian. On x86 an LE unaligned access is one
            // mov, a BE one needs a bswap -- and the JIT intrinsic may only
            // fire for native order. If the native segment is fast in LE and
            // slow in BE, the constraint is boring's FORMAT, not its backing.
            System.out.println();
            System.out.println("endianness: does native-order access rescue the segment?");
            row("write long LE  arr vs nat", "byte[]LE", "natLE",
                ab(() -> writeArrayLE(arr), () -> writeSegLE(nat), 200, 200, OPS));
            row("read  long LE  arr vs nat", "byte[]LE", "natLE",
                ab(() -> { sink += readArrayLE(arr); }, () -> { sink += readSegLE(nat); }, 200, 200, OPS));
            row("write long BE vs LE (nat)", "natBE", "natLE",
                ab(() -> writeSeg(nat), () -> writeSegLE(nat), 200, 200, OPS));
            row("write long BE vs LE (arr)", "arrBE", "arrLE",
                ab(() -> writeArray(arr), () -> writeArrayLE(arr), 200, 200, OPS));

            System.out.println();
            System.out.println("THE WORKAROUND: native-order segment access + Long.reverseBytes");
            System.out.println("(produces big-endian bytes; compare against the BE rows above)");
            row("write nat: BE-layout vs swap", "BElayout", "LE+swap",
                ab(() -> writeSeg(nat), () -> writeSegSwap(nat), 200, 200, OPS));
            row("read  nat: BE-layout vs swap", "BElayout", "LE+swap",
                ab(() -> { sink += readSeg(nat); }, () -> { sink += readSegSwap(nat); }, 200, 200, OPS));
            row("write byte[] BE vs nat swap", "byte[]", "LE+swap",
                ab(() -> writeArray(arr), () -> writeSegSwap(nat), 200, 200, OPS));
            row("read  byte[] BE vs nat swap", "byte[]", "LE+swap",
                ab(() -> { sink += readArray(arr); }, () -> { sink += readSegSwap(nat); }, 200, 200, OPS));

            // Correctness: the swap path must produce the same bytes as the
            // BE-layout path, or the speed is meaningless.
            writeSeg(nat);
            byte[] viaLayout = new byte[64];
            MemorySegment.copy(nat, ValueLayout.JAVA_BYTE, 0, viaLayout, 0, 64);
            writeSegSwap(nat);
            byte[] viaSwap = new byte[64];
            MemorySegment.copy(nat, ValueLayout.JAVA_BYTE, 0, viaSwap, 0, 64);
            System.out.println("swap path byte-identical to BE layout: "
                               + java.util.Arrays.equals(viaLayout, viaSwap));

            System.out.println();
            System.out.println("BYTE-AT-A-TIME (what a CBOR head parser does), ns per byte");
            row("sequential scan  vs heap", "byte[]", "heapseg",
                ab(() -> { sink += scanArray(arr); }, () -> { sink += scanSeg(heap); }, 200, 200, N));
            row("sequential scan  vs native", "byte[]", "nativeseg",
                ab(() -> { sink += scanArray(arr); }, () -> { sink += scanSeg(nat); }, 200, 200, N));
            row("data-dependent walk vs heap", "byte[]", "heapseg",
                ab(() -> { sink += walkArray(arr); }, () -> { sink += walkSeg(heap); }, 200, 200, N / 5));
            row("data-dependent walk vs native", "byte[]", "nativeseg",
                ab(() -> { sink += walkArray(arr); }, () -> { sink += walkSeg(nat); }, 200, 200, N / 5));

            System.out.println();
            System.out.println("ByteBuffer: one implementation WITHOUT a JDK 22 baseline");
            ByteBuffer heapBuf = ByteBuffer.allocate(N);          // BIG_ENDIAN by default
            ByteBuffer directBuf = ByteBuffer.allocateDirect(N);  // what an mmap gives you
            row("seq scan   byte[] vs heapBuf", "byte[]", "heapBuf",
                ab(() -> { sink += scanArray(arr); }, () -> { sink += scanBuf(heapBuf); }, 200, 200, N));
            row("seq scan   byte[] vs directBuf", "byte[]", "directBuf",
                ab(() -> { sink += scanArray(arr); }, () -> { sink += scanBuf(directBuf); }, 200, 200, N));
            row("walk       byte[] vs directBuf", "byte[]", "directBuf",
                ab(() -> { sink += walkArray(arr); }, () -> { sink += walkBuf(directBuf); }, 200, 200, N / 5));
            row("read  long byte[] vs directBuf", "byte[]", "directBuf",
                ab(() -> { sink += readArray(arr); }, () -> { sink += readBufLong(directBuf); }, 200, 200, OPS));
            row("write long byte[] vs directBuf", "byte[]", "directBuf",
                ab(() -> writeArray(arr), () -> writeBufLong(directBuf), 200, 200, OPS));

            System.out.println();
            System.out.println("ns per BYTE for bulk copy-out (the mmap -> heap tax)");
            System.out.printf("%-26s %10s %10s  %6s  %s%n",
                              "op", "arraycopy", "seg->arr", "ratio", "winner");
            System.out.println("-".repeat(70));
            row("bulk copy " + N + "B", "arraycopy", "seg->arr",
                ab(() -> copyArray(arr, dst), () -> copySegToArray(nat, dst), 200, 200, N));

            System.out.println("\nchecksum " + sink);
        }
    }
}
