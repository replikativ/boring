package org.replikativ.boring;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Holds the first `cap` bytes written to it, then gives up and passes
 * everything through.
 *
 * <p>EXPERIMENT for the `write-indexed!` degenerate-frame question. An indexed
 * write seals an index frame whenever it opens a stringref namespace, and on a
 * small value that frame is most of the file -- 50 bytes for `{:a 1}` where 7
 * say the same thing. `encode-indexed` buffers the whole result and can simply
 * not emit it; `write-indexed!` streams and has historically been unable to.
 *
 * <p><b>Why not just check whether the writer happened to flush.</b> The Writer
 * already tracks that, so the cheap version is "slice if `flushed == 0`". That
 * makes the OUTPUT DEPEND ON THE WRITER'S BUFFER SIZE: a `(writer 64)` spills
 * mid-value and keeps the frame, a `(writer 65536)` does not and drops it, for
 * the same value and the same options. This project has already rejected that
 * shape once -- "acceptance cannot depend on a buffering knob" -- and its own
 * streaming test deliberately uses a 64-byte writer to catch it.
 *
 * <p>So the staging window is fixed here instead, independent of the writer,
 * which is what makes the decision a function of the VALUE alone.
 */
public final class StagingStream extends OutputStream {

    private OutputStream sink;
    private final byte[] held;
    private int n;
    private boolean passthrough;

    public StagingStream(OutputStream sink, int cap) {
        this.sink = sink;
        this.held = new byte[cap];
    }

    /** Reusing a caller-owned window, so the staging costs no allocation per
     *  call. The Writer already keeps its scratch for the life of the writer
     *  ("what the writer grew, it KEEPS"), which is where this would live. */
    public StagingStream(OutputStream sink, byte[] window) {
        this.sink = sink;
        this.held = window;
    }

    /** Point a reused instance at a new sink. */
    public void reset(OutputStream ignored) { this.n = 0; this.passthrough = false; }

    @Override public void write(int b) throws IOException {
        if (passthrough) { sink.write(b); return; }
        if (n == held.length) { spill(); sink.write(b); return; }
        held[n++] = (byte) b;
    }

    @Override public void write(byte[] b, int off, int len) throws IOException {
        if (passthrough) { sink.write(b, off, len); return; }
        if (n + len > held.length) { spill(); sink.write(b, off, len); return; }
        System.arraycopy(b, off, held, n, len);
        n += len;
    }

    /** True once the value outgrew the window and the decision was forfeited. */
    public boolean overflowed() { return passthrough; }

    /** Bytes still held, i.e. the whole output when {@link #overflowed} is false. */
    public int held() { return n; }

    /** Release everything held and pass through from here on. */
    public void release() throws IOException {
        if (!passthrough) spill();
    }

    /** Release only `[from, to)` of what is held, and pass through after. The
     *  degenerate case: the envelope is dropped and the frame never written. */
    public void releaseRange(int from, int to) throws IOException {
        if (passthrough)
            throw new IllegalStateException("boring: staged bytes already passed through");
        sink.write(held, from, to - from);
        n = 0;
        passthrough = true;
    }

    private void spill() throws IOException {
        if (n > 0) sink.write(held, 0, n);
        n = 0;
        passthrough = true;
    }
}
