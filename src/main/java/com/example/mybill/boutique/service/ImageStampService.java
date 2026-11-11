package com.example.mybill.boutique.service;

import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Prints a product code on a photo (Java2D, no external service): at most 1080 px wide, a semi-transparent dark
 * rounded box in the bottom-right corner with the code in white bold DejaVu Sans (about width / 20), JPEG out.
 * Needs a headless JVM with fonts (Dockerfile: fontconfig + fonts-dejavu-core, -Djava.awt.headless=true).
 */
@Service
public class ImageStampService {

    public static final int MAX_WIDTH = 1080;
    static final String FONT = "DejaVu Sans";

    public byte[] stampCode(byte[] bytes, String code) {
        BufferedImage img = toRgb(fit(read(bytes), MAX_WIDTH));
        Graphics2D g = img.createGraphics();
        try {
            smooth(g);
            int w = img.getWidth(), h = img.getHeight();
            Font font = new Font(FONT, Font.BOLD, Math.max(14, w / 20));
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int padX = font.getSize() / 2, padY = font.getSize() / 3, margin = Math.max(8, w / 40);
            int boxW = fm.stringWidth(code) + 2 * padX, boxH = fm.getAscent() + fm.getDescent() + 2 * padY;
            int x = w - margin - boxW, y = h - margin - boxH;
            g.setColor(new Color(0, 0, 0, 160));
            g.fill(new RoundRectangle2D.Float(x, y, boxW, boxH, font.getSize() * 0.6f, font.getSize() * 0.6f));
            g.setColor(Color.WHITE);
            g.drawString(code, x + padX, y + padY + fm.getAscent());
        } finally {
            g.dispose();
        }
        return jpeg(img, 0.88f);
    }

    // ── shared helpers (also used by CollageService) ─────────────────────────

    static BufferedImage read(byte[] bytes) {
        try {
            BufferedImage img = bytes == null ? null : ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) throw new IllegalArgumentException("Not a readable image (JPEG / PNG expected)");
            return img;
        } catch (IOException e) {
            throw new IllegalArgumentException("Image could not be read: " + e.getMessage(), e);
        }
    }

    /** Scaled down to maxWidth (aspect kept); smaller images are returned as they are. */
    static BufferedImage fit(BufferedImage src, int maxWidth) {
        if (src.getWidth() <= maxWidth) return src;
        int h = Math.max(1, Math.round(src.getHeight() * (maxWidth / (float) src.getWidth())));
        BufferedImage out = new BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        smooth(g);
        g.drawImage(src, 0, 0, maxWidth, h, null);
        g.dispose();
        return out;
    }

    /** JPEG has no alpha: draw onto white RGB. */
    static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    static byte[] jpeg(BufferedImage img, float quality) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), p);
            writer.dispose();
            ios.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("JPEG encoding failed: " + e.getMessage(), e);
        }
    }
}
