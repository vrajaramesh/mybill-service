package com.example.mybill.boutique.service;

import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * One preview image of a collection (Java2D): a title strip (collection name + price) above a grid of square
 * thumbnails (up to 5 columns), each labelled with its variant number (01, 02, …). JPEG out.
 */
@Service
public class CollageService {

    static final int MAX_COLUMNS = 5;
    static final int WIDTH = 1080;

    /** One tile: its label ("01") and the photo bytes (an unreadable photo becomes a grey tile). */
    public record Tile(String label, byte[] image) {}

    public byte[] build(String title, String priceLine, List<Tile> tiles) {
        int n = Math.max(tiles.size(), 1);
        int cols = Math.min(MAX_COLUMNS, Math.max(1, (int) Math.ceil(Math.sqrt(n))));
        int rows = (int) Math.ceil(n / (double) cols);
        int cell = WIDTH / cols;
        int strip = Math.max(110, WIDTH / 9);
        BufferedImage out = new BufferedImage(cell * cols, strip + cell * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            ImageStampService.smooth(g);
            g.setColor(new Color(250, 247, 242));
            g.fillRect(0, 0, out.getWidth(), out.getHeight());

            // title strip
            g.setColor(new Color(60, 30, 50));
            g.fillRect(0, 0, out.getWidth(), strip);
            g.setColor(Color.WHITE);
            Font titleFont = fitFont(g, title, new Font(ImageStampService.FONT, Font.BOLD, strip * 2 / 5), out.getWidth() - 40);
            g.setFont(titleFont);
            g.drawString(title, 20, strip / 2);
            if (priceLine != null && !priceLine.isBlank()) {
                g.setFont(new Font(ImageStampService.FONT, Font.PLAIN, strip / 4));
                g.setColor(new Color(255, 214, 150));
                g.drawString(priceLine, 20, strip - strip / 6);
            }

            // grid
            int gap = Math.max(4, cell / 40);
            Font labelFont = new Font(ImageStampService.FONT, Font.BOLD, Math.max(14, cell / 7));
            for (int i = 0; i < tiles.size(); i++) {
                int x = (i % cols) * cell, y = strip + (i / cols) * cell;
                int size = cell - 2 * gap;
                drawSquare(g, tiles.get(i).image(), x + gap, y + gap, size);
                g.setFont(labelFont);
                FontMetrics fm = g.getFontMetrics();
                String label = tiles.get(i).label();
                int padX = labelFont.getSize() / 3, bw = fm.stringWidth(label) + 2 * padX, bh = fm.getAscent() + fm.getDescent();
                g.setColor(new Color(0, 0, 0, 170));
                g.fill(new RoundRectangle2D.Float(x + gap * 2, y + gap * 2, bw, bh, bh / 2f, bh / 2f));
                g.setColor(Color.WHITE);
                g.drawString(label, x + gap * 2 + padX, y + gap * 2 + fm.getAscent());
            }
        } finally {
            g.dispose();
        }
        return ImageStampService.jpeg(out, 0.85f);
    }

    /** Centre-cropped to a square and scaled into the cell. */
    private static void drawSquare(Graphics2D g, byte[] bytes, int x, int y, int size) {
        BufferedImage src;
        try {
            src = ImageStampService.read(bytes);
        } catch (Exception e) {
            g.setColor(new Color(220, 220, 220));
            g.fillRect(x, y, size, size);
            return;
        }
        int side = Math.min(src.getWidth(), src.getHeight());
        int sx = (src.getWidth() - side) / 2, sy = (src.getHeight() - side) / 2;
        g.drawImage(src, x, y, x + size, y + size, sx, sy, sx + side, sy + side, null);
    }

    /** The font, shrunk until the text fits maxWidth. */
    private static Font fitFont(Graphics2D g, String text, Font font, int maxWidth) {
        Font f = font;
        while (f.getSize() > 14 && g.getFontMetrics(f).stringWidth(text) > maxWidth) f = f.deriveFont((float) f.getSize() - 2);
        return f;
    }
}
