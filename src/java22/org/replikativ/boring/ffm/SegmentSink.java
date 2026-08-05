package org.replikativ.boring.ffm;

import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * An {@link OutputStream} that writes into an FFM {@link MemorySegment} -- the
 * off-heap and memory-mapped write path, and the mirror of {@link SegmentSource}.
 *
 * <p>This is an OutputStream rather than a sink type of its own because
 * {@code Writer} compiles at {@code --release 9} and cannot name a
 * MemorySegment at all. Keeping the writer's sink an OutputStream is what lets
 * the streaming encoder work off-heap without dragging FFM into the JDK 9
 * source set, which is the constraint the whole two-source-set arrangement
 * exists to preserve.
 *
 * <p>Each chunk becomes one bulk {@code MemorySegment.copy} -- a memcpy, not a
 * per-byte loop. There is no zero-copy variant and there does not need to be:
 * boring's encoder builds bytes in a heap buffer, and moving 64 KB of them is
 * far cheaper than producing them.
 *
 * <p><b>Writing to a MAPPED segment is usually the wrong choice.</b> Measured
 * in doc/STORAGE.md: appending 200 000 items costs 130 ms through a
 * BufferedOutputStream and 171 ms through a mapping, because a mapping faults
 * per page while {@code write(2)} hands the kernel one prepared buffer. Use
 * this for off-heap buffers a native peer will read, or where a mapping is
 * already open for other reasons -- not as a faster file writer.
 *
 * <p>Not thread-safe, and it does not own the segment: the caller's Arena
 * governs the lifetime, and writing after that Arena closes throws a typed FFM
 * error rather than corrupting memory.
 */
public final class SegmentSink extends OutputStream {

    private final MemorySegment seg;
    private final long capacity;
    private long pos;

    public SegmentSink(MemorySegment seg) {
        this.seg = seg;
        this.capacity = seg.byteSize();
    }

    public static SegmentSink of(MemorySegment seg) { return new SegmentSink(seg); }

    /** Bytes written so far. */
    public long position() { return pos; }

    /** A slice covering exactly what has been written, for handing on. */
    public MemorySegment written() { return seg.asSlice(0, pos); }

    @Override
    public void write(int b) {
        checkRoom(1);
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) b);
        pos++;
    }

    @Override
    public void write(byte[] src, int off, int len) {
        // SUBTRACTION after the non-negativity checks, never addition. This is
        // a public OutputStream, so the arguments are not necessarily the
        // writer's own: `off + len` can overflow to a negative and slip past a
        // `>` comparison, leaving the real bounds check to a lower layer.
        if (off < 0 || len < 0 || off > src.length - len)
            throw new IndexOutOfBoundsException(
                "off=" + off + " len=" + len + " src=" + src.length);
        checkRoom(len);
        MemorySegment.copy(src, off, seg, ValueLayout.JAVA_BYTE, pos, len);
        pos += len;
    }

    /** Overflow is an IllegalStateException, NOT a silent truncation. A sink
     *  that quietly stopped accepting bytes would hand back a document missing
     *  its tail, and the index frame is at the END -- so the failure would look
     *  like a corrupt index rather than a full buffer. */
    private void checkRoom(long n) {
        // `capacity - pos` rather than `pos + n`: a segment near Long.MAX_VALUE
        // would overflow the addition.
        if (n > capacity - pos)
            throw new IllegalStateException(
                "boring: segment of " + capacity + " bytes is full at " + pos
                + " and cannot take " + n + " more");
    }
}
