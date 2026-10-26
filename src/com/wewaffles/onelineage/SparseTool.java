package com.wewaffles.onelineage;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * Converts an Android sparse image (the format fastboot and most stock
 * firmware packages use for system.img) into a raw, loop-mountable image.
 * Equivalent to AOSP's simg2img.
 *
 * It has no Android dependencies so the app can run it as root, straight out
 * of its own APK, against files the app process itself cannot open:
 *
 *   CLASSPATH=/data/app/.../base.apk app_process /system/bin \
 *       com.wewaffles.onelineage.SparseTool in.img out.raw
 *
 * Exit codes: 0 ok, 1 usage, 2 not a sparse image, 3 malformed/IO error.
 */
public final class SparseTool {

    static final int SPARSE_MAGIC = 0xED26FF3A;
    static final int CHUNK_RAW = 0xCAC1;
    static final int CHUNK_FILL = 0xCAC2;
    static final int CHUNK_DONT_CARE = 0xCAC3;
    static final int CHUNK_CRC32 = 0xCAC4;

    private SparseTool() {}

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: SparseTool <sparse.img> <out.raw>");
            System.exit(1);
        }
        try {
            long bytes = unsparse(args[0], args[1], true);
            System.out.println("unsparsed " + bytes + " bytes -> " + args[1]);
            System.exit(0);
        } catch (NotSparseException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(2);
        } catch (EOFException e) {
            System.err.println("ERROR: sparse conversion failed: truncated sparse image (incomplete download or copy?)");
            System.exit(3);
        } catch (Exception e) {
            String why = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            System.err.println("ERROR: sparse conversion failed: " + why);
            System.exit(3);
        }
    }

    static final class NotSparseException extends IOException {
        NotSparseException(String msg) { super(msg); }
    }

    /** Returns the size of the raw image written to {@code outPath}. */
    static long unsparse(String inPath, String outPath, boolean progress) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(inPath), 1 << 20));
             RandomAccessFile out = new RandomAccessFile(outPath, "rw")) {

            if (readLE32(in) != SPARSE_MAGIC) {
                throw new NotSparseException(inPath + " is not an Android sparse image");
            }
            int major = readLE16(in);
            readLE16(in); // minor
            int fileHdrSz = readLE16(in);
            int chunkHdrSz = readLE16(in);
            long blkSz = readLE32(in) & 0xFFFFFFFFL;
            long totalBlks = readLE32(in) & 0xFFFFFFFFL;
            long totalChunks = readLE32(in) & 0xFFFFFFFFL;
            readLE32(in); // image checksum, unused by every real producer

            if (major != 1) throw new IOException("unsupported sparse major version " + major);
            if (fileHdrSz < 28 || chunkHdrSz < 12) throw new IOException("bad sparse header sizes");
            if (blkSz == 0 || blkSz % 4 != 0) throw new IOException("bad block size " + blkSz);
            skipFully(in, fileHdrSz - 28);

            final long outSize = totalBlks * blkSz;
            out.setLength(0);
            byte[] buf = new byte[1 << 20];
            long pos = 0;
            int lastPct = -1;

            for (long c = 0; c < totalChunks; c++) {
                int type = readLE16(in);
                readLE16(in); // reserved
                long chunkBlks = readLE32(in) & 0xFFFFFFFFL;
                long totalSz = readLE32(in) & 0xFFFFFFFFL;
                skipFully(in, chunkHdrSz - 12);
                long dataSz = totalSz - chunkHdrSz;
                long span = chunkBlks * blkSz;

                switch (type) {
                    case CHUNK_RAW: {
                        if (dataSz != span) throw new IOException("raw chunk " + c + " size mismatch");
                        out.seek(pos);
                        long left = span;
                        while (left > 0) {
                            int n = (int) Math.min(buf.length, left);
                            in.readFully(buf, 0, n);
                            out.write(buf, 0, n);
                            left -= n;
                        }
                        pos += span;
                        break;
                    }
                    case CHUNK_FILL: {
                        if (dataSz != 4) throw new IOException("fill chunk " + c + " has " + dataSz + " data bytes");
                        byte[] pattern = new byte[4];
                        in.readFully(pattern);
                        boolean zero = pattern[0] == 0 && pattern[1] == 0 && pattern[2] == 0 && pattern[3] == 0;
                        if (!zero) {
                            for (int i = 0; i < buf.length; i++) buf[i] = pattern[i & 3];
                            out.seek(pos);
                            long left = span;
                            while (left > 0) {
                                int n = (int) Math.min(buf.length, left);
                                out.write(buf, 0, n);
                                left -= n;
                            }
                        }
                        // A zero fill is left as a hole, which reads back as zeros.
                        pos += span;
                        break;
                    }
                    case CHUNK_DONT_CARE:
                        skipFully(in, dataSz);
                        pos += span;
                        break;
                    case CHUNK_CRC32:
                        skipFully(in, dataSz);
                        break;
                    default:
                        throw new IOException(String.format("unknown chunk type 0x%04x at chunk %d", type, c));
                }

                if (progress && outSize > 0) {
                    int pct = (int) (pos * 100 / outSize);
                    if (pct / 10 != lastPct / 10) {
                        System.out.println("unsparse " + pct + "%");
                        lastPct = pct;
                    }
                }
            }

            if (pos != outSize) throw new IOException("chunks cover " + pos + " bytes, header says " + outSize);
            out.setLength(outSize);
            return outSize;
        }
    }

    private static int readLE16(InputStream in) throws IOException {
        int a = in.read(), b = in.read();
        if ((a | b) < 0) throw new EOFException("truncated sparse image");
        return a | (b << 8);
    }

    private static int readLE32(InputStream in) throws IOException {
        int a = in.read(), b = in.read(), c = in.read(), d = in.read();
        if ((a | b | c | d) < 0) throw new EOFException("truncated sparse image");
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    private static void skipFully(DataInputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException("truncated sparse image");
                s = 1;
            }
            n -= s;
        }
    }
}
