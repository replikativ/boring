package org.replikativ.boring;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A {@link ByteSource} over a {@link ByteBuffer} -- the adapter for every
 * library that hands out a buffer instead of an array.
 *
 * <p><b>Why this exists.</b> {@link org.replikativ.boring.ffm.SegmentSource} is
 * the off-heap path, and it is the right one for a file boring itself mapped.
 * But it needs JDK 22, and a great deal of software hands out a
 * {@code ByteBuffer}: NIO channels, Netty, {@code MappedByteBuffer}, and log
 * engines whose whole value proposition is that a payload is a read-only slice
 * into an mmap that was never copied. Reading one of those meant
 * {@code payload -> byte[] -> Reader}, which allocates and copies exactly the
 * bytes the caller went to trouble to avoid copying.
 *
 * <p>This runs on JDK 9 alongside the rest of {@code src/java}, so it is also
 * the only off-heap source available to a caller who cannot yet move to 22.
 *
 * <p><b>Absolute accessors only.</b> Every read here is indexed from the
 * buffer's own zero, and none of them touch {@code position}. That is what
 * makes a source shareable with the code that produced the buffer: a decode
 * cannot disturb a cursor the caller is still using, and the same buffer can
 * back two sources at once. The Reader never reads sequentially anyway -- it
 * seeks.
 *
 * <p><b>Order is forced to big-endian</b> in the constructor, on a duplicate.
 * CBOR is big-endian throughout, {@code ByteBuffer}'s default is big-endian but
 * is a mutable property of the buffer, and a caller who set
 * {@code LITTLE_ENDIAN} for their own framing would otherwise get every
 * multi-byte head silently byte-swapped -- lengths and floats wrong, with no
 * error, which is the worst failure this codebase can produce. Duplicating is
 * cheap (no data copy) and leaves the caller's buffer untouched, including its
 * position and its order.
 */
public final class BufferSource implements ByteSource {

    private final ByteBuffer buf;
    private final int size;
    private final int base;
    private final byte[] arr;

    public BufferSource(ByteBuffer buf) {
        // duplicate(): the order and the position below are OURS, not the
        // caller's. A read-only buffer duplicates to a read-only buffer, so
        // this cannot be used to write through one.
        this.buf = buf.duplicate().order(ByteOrder.BIG_ENDIAN);
        this.size = this.buf.remaining();
        // The buffer's zero is its POSITION, not index 0 of its backing store,
        // so every accessor is offset by it. Rewinding the duplicate to 0 would
        // be wrong for a buffer sliced by position and right for one from
        // `slice()`; taking `remaining()` and adding `position()` is correct
        // for both. Read once into a final field -- nothing here ever moves the
        // position, so it cannot change, and the JIT folds a final int where it
        // would have to reload a getter.
        this.base = this.buf.position();
        this.arr = heapArrayOf(this.buf);
    }

    /**
     * The backing array, but only when the buffer IS that array in its
     * entirety -- the Reader's fast path assumes index 0 of the array is offset
     * 0 of the source, so a view onto part of an array would alias the wrong
     * bytes. A read-only buffer never qualifies: {@code hasArray()} is false
     * for one by contract, which is exactly right, since the fast path would
     * hand out a mutable reference to memory the caller marked read-only.
     */
    private static byte[] heapArrayOf(ByteBuffer b) {
        if (!b.hasArray()) return null;
        byte[] a = b.array();
        if (b.arrayOffset() != 0) return null;
        if (b.position() != 0) return null;
        if (a.length != b.remaining()) return null;
        return a;
    }

    /** Wrap a buffer as a source the Reader accepts. */
    public static ByteSource of(ByteBuffer b) { return new BufferSource(b); }

    /**
     * The duplicate this reads through. Not the caller's buffer -- see the
     * class comment -- so mutating its position is harmless.
     */
    public ByteBuffer buffer() { return buf; }

    @Override public long size() { return size; }

    @Override public byte at(long p) { return buf.get(base + (int) p); }

    @Override public short i16(long p) { return buf.getShort(base + (int) p); }

    @Override public int i32(long p) { return buf.getInt(base + (int) p); }

    @Override public long i64(long p) { return buf.getLong(base + (int) p); }

    @Override public void copyTo(long p, byte[] dst, int off, int n) {
        // A second duplicate rather than get(int,byte[],int,int), which is JDK
        // 13+. This class targets 9, and the duplicate is a header allocation
        // against a bulk copy -- not free, but only paid for byte strings and
        // text, never for structure, which is what navigation touches.
        ByteBuffer d = buf.duplicate();
        d.position(base + (int) p);
        d.get(dst, off, n);
    }

    @Override public byte[] heapArray() { return arr; }
}
