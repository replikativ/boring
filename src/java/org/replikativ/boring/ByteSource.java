package org.replikativ.boring;

/**
 * A random-access source of bytes the Reader can decode from, named in terms
 * JDK 9 understands.
 *
 * <p><b>Why this exists.</b> The Reader wants to serve both heap buffers and
 * mmap'ed files from ONE head parser -- two parsers is how a decoder and its
 * navigator drift apart silently. The natural way to do that is to read
 * through a {@code MemorySegment}, but the final FFM API is JDK 22, and
 * naming it in this class would (a) refuse to compile under
 * {@code --release 9} and (b) emit a class file JDK 21 cannot load at all.
 *
 * <p>So the segment never appears here. {@link org.replikativ.boring.ffm.SegmentSource}
 * implements this interface and is compiled separately against JDK 22; a JDK 9
 * process simply never loads it. One jar can hold class files of mixed
 * versions, because the JVM only rejects a class when it LOADS it.
 *
 * <p>Note what is NOT on this interface: the byte[] fast path. The Reader
 * keeps a direct {@code byte[]} reference and branches on it, so decoding from
 * a heap array never makes a virtual call through here at all. That branch is
 * worth 14-50% -- see the accessor comment in Reader.
 *
 * <p>All multi-byte accessors return BIG-ENDIAN values, because that is what
 * CBOR is. An implementation over native memory should load in NATIVE order
 * and {@code reverseBytes}, never {@code withOrder(BIG_ENDIAN)}, which costs
 * 4.1x by declining to intrinsify.
 */
public interface ByteSource {

    /** Total addressable size in bytes. */
    long size();

    /** The signed byte at `p`. */
    byte at(long p);

    /** Big-endian 16-bit value at `p`. Unaligned. */
    short i16(long p);

    /** Big-endian 32-bit value at `p`. Unaligned. */
    int i32(long p);

    /** Big-endian 64-bit value at `p`. Unaligned. */
    long i64(long p);

    /** Copy `n` bytes from `p` into `dst` at `off`. */
    void copyTo(long p, byte[] dst, int off, int n);

    /**
     * The whole backing array, if this source IS one -- letting the Reader
     * take its byte[] fast path and skip this interface entirely.
     *
     * Must return null unless the array is the source in its entirety: the
     * Reader assumes offset 0, so a view onto part of an array would alias
     * the wrong bytes.
     */
    byte[] heapArray();
}
