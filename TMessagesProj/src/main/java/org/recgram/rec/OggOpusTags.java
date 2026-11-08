package org.recgram.rec;

import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Замена блока OpusTags во второй странице Ogg-файла: так данные звонка хранятся внутри .opus.
 * Звук не перекодируется, меняется только страница с тегами.
 */
final class OggOpusTags {

    private static final int[] CRC = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            }
            CRC[i] = r;
        }
    }

    private OggOpusTags() {
    }

    /** Переписывает теги. Возвращает false, если файл устроен иначе, чем ожидалось (файл не меняется). */
    static boolean write(File file, String vendor, Map<String, String> tags) {
        File tmp = new File(file.getParentFile(), file.getName() + ".tags");
        try (InputStream in = new FileInputStream(file)) {
            byte[] page1 = readPage(in);
            byte[] page2 = readPage(in);
            if (page1 == null || page2 == null || !startsWith(payload(page1), "OpusHead") || !startsWith(payload(page2), "OpusTags")) {
                return false;
            }
            int nseg = page2[26] & 0xff;
            if (nseg == 0 || (page2[27 + nseg - 1] & 0xff) == 255) {
                return false; // теги продолжаются на следующей странице — не трогаем
            }
            byte[] packet = buildTags(vendor, tags);
            if (packet.length >= 255 * 255) {
                return false;
            }
            byte[] newPage2 = buildPage(page2, packet);
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(page1);
                out.write(newPage2);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
            tmp.delete();
            return false;
        }
        if (!file.delete() || !tmp.renameTo(file)) {
            tmp.delete();
            return false;
        }
        return true;
    }

    private static byte[] readPage(InputStream in) throws IOException {
        byte[] head = new byte[27];
        if (!readFully(in, head) || !startsWith(head, "OggS")) {
            return null;
        }
        int nseg = head[26] & 0xff;
        byte[] seg = new byte[nseg];
        if (!readFully(in, seg)) {
            return null;
        }
        int len = 0;
        for (byte b : seg) {
            len += b & 0xff;
        }
        byte[] body = new byte[len];
        if (!readFully(in, body)) {
            return null;
        }
        byte[] page = new byte[27 + nseg + len];
        System.arraycopy(head, 0, page, 0, 27);
        System.arraycopy(seg, 0, page, 27, nseg);
        System.arraycopy(body, 0, page, 27 + nseg, len);
        return page;
    }

    private static byte[] payload(byte[] page) {
        int nseg = page[26] & 0xff;
        byte[] p = new byte[page.length - 27 - nseg];
        System.arraycopy(page, 27 + nseg, p, 0, p.length);
        return p;
    }

    private static byte[] buildTags(String vendor, Map<String, String> tags) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write("OpusTags".getBytes(StandardCharsets.US_ASCII), 0, 8);
        byte[] v = vendor.getBytes(StandardCharsets.UTF_8);
        le32(b, v.length);
        b.write(v, 0, v.length);
        le32(b, tags.size());
        for (Map.Entry<String, String> e : tags.entrySet()) {
            byte[] c = (e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue())).getBytes(StandardCharsets.UTF_8);
            le32(b, c.length);
            b.write(c, 0, c.length);
        }
        return b.toByteArray();
    }

    /** Новая страница с теми же заголовком, серийным и порядковым номером, но другим содержимым. */
    private static byte[] buildPage(byte[] old, byte[] packet) {
        int full = packet.length / 255;
        int nseg = full + 1;
        byte[] page = new byte[27 + nseg + packet.length];
        System.arraycopy(old, 0, page, 0, 26);
        page[26] = (byte) nseg;
        for (int i = 0; i < full; i++) {
            page[27 + i] = (byte) 255;
        }
        page[27 + full] = (byte) (packet.length % 255);
        System.arraycopy(packet, 0, page, 27 + nseg, packet.length);
        page[22] = page[23] = page[24] = page[25] = 0;
        int crc = 0;
        for (byte x : page) {
            crc = (crc << 8) ^ CRC[((crc >>> 24) ^ (x & 0xff)) & 0xff];
        }
        page[22] = (byte) crc;
        page[23] = (byte) (crc >>> 8);
        page[24] = (byte) (crc >>> 16);
        page[25] = (byte) (crc >>> 24);
        return page;
    }

    private static void le32(ByteArrayOutputStream b, int v) {
        b.write(v & 0xff);
        b.write((v >>> 8) & 0xff);
        b.write((v >>> 16) & 0xff);
        b.write((v >>> 24) & 0xff);
    }

    private static boolean startsWith(byte[] a, String s) {
        if (a.length < s.length()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (a[i] != (byte) s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean readFully(InputStream in, byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int r = in.read(dst, off, dst.length - off);
            if (r < 0) {
                return false;
            }
            off += r;
        }
        return true;
    }
}
