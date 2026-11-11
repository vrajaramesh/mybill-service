package com.example.mybill.boutique.service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * dHash (difference hash): the image shrunk to 9x8 grey pixels, one bit per "left brighter than right". 64 bits as
 * 16 hex chars; near-duplicates (re-saved, resized or lightly re-compressed copies) differ in only a few bits.
 */
public final class ImageHash {

    /** Hamming distance at or below which two photos count as the same picture. */
    public static final int NEAR_DUPLICATE_BITS = 5;

    private ImageHash() {}

    /** The dHash of a JPEG / PNG, or null if the bytes can't be decoded (e.g. WebP). */
    public static String dHash(byte[] bytes) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
            if (src == null) return null;
            BufferedImage small = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, 9, 8, null);
            g.dispose();
            long bits = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int left = small.getRaster().getSample(x, y, 0), right = small.getRaster().getSample(x + 1, y, 0);
                    bits = (bits << 1) | (left > right ? 1 : 0);
                }
            }
            return String.format("%016x", bits);
        } catch (Exception e) {
            return null;
        }
    }

    public static int distance(String a, String b) {
        if (a == null || b == null || a.length() != 16 || b.length() != 16) return 64;
        return Long.bitCount(Long.parseUnsignedLong(a, 16) ^ Long.parseUnsignedLong(b, 16));
    }
}
