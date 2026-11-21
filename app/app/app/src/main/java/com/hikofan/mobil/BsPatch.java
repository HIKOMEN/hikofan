package com.hikofan.mobil;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * bsdiff (BSDIFF40) yamasini uygular: eski APK + kucuk yama dosyasi = yeni APK.
 * Dosyalari parca parca isler, bellekte buyuk dizi tutmaz.
 */
public final class BsPatch {

    private BsPatch() {
    }

    /** Yama dosyasinin bir bolumunu (ctrl / diff / extra) ayri bir akis gibi okur. */
    private static final class Region extends InputStream {
        private final RandomAccessFile raf;
        private final long start;
        private final long length;
        private long pos = 0;

        Region(File f, long start, long length) throws IOException {
            this.raf = new RandomAccessFile(f, "r");
            this.start = start;
            this.length = length;
        }

        @Override
        public int read() throws IOException {
            if (pos >= length) return -1;
            raf.seek(start + pos);
            int b = raf.read();
            if (b >= 0) pos++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= length) return -1;
            int n = (int) Math.min((long) len, length - pos);
            raf.seek(start + pos);
            int r = raf.read(b, off, n);
            if (r > 0) pos += r;
            return r;
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    private static long offtin(byte[] b, int o) {
        long y = b[o + 7] & 0x7F;
        for (int i = 6; i >= 0; i--) {
            y = y * 256 + (b[o + i] & 0xFF);
        }
        if ((b[o + 7] & 0x80) != 0) y = -y;
        return y;
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new EOFException("yama beklenenden kisa");
            off += n;
        }
    }

    private static InputStream bz(File patch, long start, long length) throws IOException {
        return new BZip2CompressorInputStream(new BufferedInputStream(new Region(patch, start, length), 65536));
    }

    public static void patch(File oldFile, File patchFile, File outFile) throws IOException {
        long plen = patchFile.length();
        byte[] head = new byte[32];
        try (InputStream in = new FileInputStream(patchFile)) {
            readFully(in, head, 32);
        }
        if (!"BSDIFF40".equals(new String(head, 0, 8, "US-ASCII"))) {
            throw new IOException("yama bicimi hatali");
        }
        long ctrlLen = offtin(head, 8);
        long diffLen = offtin(head, 16);
        long newSize = offtin(head, 24);
        if (ctrlLen < 0 || diffLen < 0 || newSize < 0 || 32 + ctrlLen + diffLen > plen) {
            throw new IOException("yama bozuk");
        }

        InputStream ctrl = null, diff = null, extra = null;
        RandomAccessFile old = null;
        try {
            ctrl = bz(patchFile, 32, ctrlLen);
            diff = bz(patchFile, 32 + ctrlLen, diffLen);
            extra = bz(patchFile, 32 + ctrlLen + diffLen, plen - 32 - ctrlLen - diffLen);
            old = new RandomAccessFile(oldFile, "r");
            long oldSize = old.length();

            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
                byte[] cbuf = new byte[24];
                byte[] dbuf = new byte[65536];
                byte[] obuf = new byte[65536];
                long oldpos = 0;
                long newpos = 0;
                while (newpos < newSize) {
                    readFully(ctrl, cbuf, 24);
                    long x = offtin(cbuf, 0);
                    long y = offtin(cbuf, 8);
                    long z = offtin(cbuf, 16);
                    if (x < 0 || y < 0 || newpos + x + y > newSize) {
                        throw new IOException("yama bozuk (ctrl)");
                    }

                    // fark bolumu: yeni = fark + eski (eski dosyanin disi 0 sayilir)
                    long left = x;
                    while (left > 0) {
                        int n = (int) Math.min(left, (long) dbuf.length);
                        readFully(diff, dbuf, n);
                        Arrays.fill(obuf, 0, n, (byte) 0);
                        long s = Math.max(oldpos, 0L);
                        long e = Math.min(oldpos + n, oldSize);
                        if (e > s) {
                            old.seek(s);
                            old.readFully(obuf, (int) (s - oldpos), (int) (e - s));
                        }
                        for (int i = 0; i < n; i++) {
                            dbuf[i] = (byte) (dbuf[i] + obuf[i]);
                        }
                        out.write(dbuf, 0, n);
                        oldpos += n;
                        newpos += n;
                        left -= n;
                    }

                    // ekstra bolum: oldugu gibi kopyalanir
                    left = y;
                    while (left > 0) {
                        int n = (int) Math.min(left, (long) dbuf.length);
                        readFully(extra, dbuf, n);
                        out.write(dbuf, 0, n);
                        newpos += n;
                        left -= n;
                    }

                    oldpos += z;
                }
            }
        } finally {
            if (ctrl != null) ctrl.close();
            if (diff != null) diff.close();
            if (extra != null) extra.close();
            if (old != null) old.close();
        }
    }
}
