package org.replikativ.boring.ffm;

import org.replikativ.boring.ByteSource;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * A {@link ByteSource} over an FFM {@link MemorySegment} -- the mmap'ed-file
 * and off-heap path.
 *
 * <p>Compiled against JDK 22, separately from everything in {@code src/java},
 * which targets 9. A JDK 9 process never loads this class, so the Reader
 * still runs there; ask for a segment on an old JVM and you get a
 * NoClassDefFoundError at that call, not at decode time.
 *
 * <p><b>Never {@code withOrder(BIG_ENDIAN)}.</b> A non-native ValueLayout is
 * not intrinsified: big-endian access to an off-heap segment measures 5.63 ns
 * against 1.37 for native order, a 4.1x cliff, and CBOR is big-endian
 * throughout. Every accessor here loads in NATIVE order and swaps explicitly.
 * {@code Long.reverseBytes} is a bswap intrinsic, so the swap is free, and
 * {@code SWAP} is a static final constant so the branch folds at JIT time --
 * which also makes this correct on a big-endian host, where no swap is wanted.
 */
public final class SegmentSource implements ByteSource {

    private static final ValueLayout.OfByte  B = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfShort S = ValueLayout.JAVA_SHORT_UNALIGNED;
    private static final ValueLayout.OfInt   I = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong  L = ValueLayout.JAVA_LONG_UNALIGNED;

    private static final boolean SWAP =
        ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    private final MemorySegment seg;
    private final long size;
    private final byte[] arr;

    public SegmentSource(MemorySegment seg) {
        this.seg = seg;
        this.size = seg.byteSize();
        Object base = seg.heapBase().orElse(null);
        // Only when the segment IS the whole array -- heapBase() hands back the
        // array but not this segment's offset within it, so a slice would let
        // the Reader's fast path read from the wrong place.
        this.arr = (base instanceof byte[] && ((byte[]) base).length == this.size)
            ? (byte[]) base : null;
    }

    /** Wrap a segment as a source the Reader accepts. */
    public static ByteSource of(MemorySegment seg) { return new SegmentSource(seg); }

    public MemorySegment segment() { return seg; }

    @Override public long size() { return size; }

    @Override public byte at(long p) { return seg.get(B, p); }

    @Override public short i16(long p) {
        short v = seg.get(S, p);
        return SWAP ? Short.reverseBytes(v) : v;
    }

    @Override public int i32(long p) {
        int v = seg.get(I, p);
        return SWAP ? Integer.reverseBytes(v) : v;
    }

    @Override public long i64(long p) {
        long v = seg.get(L, p);
        return SWAP ? Long.reverseBytes(v) : v;
    }

    @Override public void copyTo(long p, byte[] dst, int off, int n) {
        MemorySegment.copy(seg, B, p, dst, off, n);
    }

    @Override public byte[] heapArray() { return arr; }
}
