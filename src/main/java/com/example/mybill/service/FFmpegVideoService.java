package com.example.mybill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

@Service
@Profile("!local")
public class FFmpegVideoService {

    @Autowired private GcsUploadService gcsUploadService;

    private static final Logger log = Logger.getLogger(FFmpegVideoService.class.getName());

    private static final String MUSIC_URL =
        "https://storage.googleapis.com/srisa-reels-assets/music/upbeat-ethnic-01.mp3";

    // 9:16 portrait — Instagram Reels max resolution
    private static final int WIDTH  = 1080;
    private static final int HEIGHT = 1920;
    private static final int FPS    = 30;
    // Each slide duration in seconds (matches Creatomate template)
    private static final double SLIDE_DURATION = 3.8;
    // Ken Burns: zoom from 100% to 115% over slide duration
    private static final double ZOOM_END = 1.15;

    /**
     * Generates a high-quality 1080x1920 MP4 slideshow from the given image URLs,
     * uploads it to GCS, and returns the public GCS URL.
     */
    public String generateSlideshow(List<String> imageUrls, String productName) throws Exception {
        Path tempDir = Files.createTempDirectory("reels-");
        try {
            // 1. Download images
            List<Path> imagePaths = new ArrayList<>();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
            for (int i = 0; i < imageUrls.size(); i++) {
                Path dest = tempDir.resolve("img_" + i + fileExt(imageUrls.get(i)));
                download(http, imageUrls.get(i), dest);
                imagePaths.add(dest);
            }

            // 2. Download background music
            Path musicPath = tempDir.resolve("music.mp3");
            download(http, MUSIC_URL, musicPath);

            // 3. Run FFmpeg
            Path output = tempDir.resolve("reel.mp4");
            runFFmpeg(imagePaths, musicPath, output, productName);

            // 4. Upload to GCS and return URL
            byte[] videoBytes = Files.readAllBytes(output);
            String url = gcsUploadService.uploadVideo(videoBytes);
            log.info("[FFmpeg] Uploaded reel to GCS: " + url + " (" + videoBytes.length / 1024 + " KB)");
            return url;

        } finally {
            deleteTempDir(tempDir);
        }
    }

    private void runFFmpeg(List<Path> images, Path music, Path output, String productName) throws Exception {
        int n            = images.size();
        double totalSecs = n * SLIDE_DURATION;

        // 1.15x dimensions — image is pre-scaled to this, then progressively cropped
        // to simulate Ken Burns zoom-in (100% → 115%) without zoompan's memory cost
        int scaledW = (int) (WIDTH  * ZOOM_END); // 1242
        int scaledH = (int) (HEIGHT * ZOOM_END); // 2208

        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");

        for (Path img : images) {
            cmd.addAll(List.of("-loop", "1", "-t", String.valueOf(SLIDE_DURATION),
                               "-r", String.valueOf(FPS),
                               "-i", img.toAbsolutePath().toString()));
        }
        cmd.addAll(List.of("-i", music.toAbsolutePath().toString()));

        StringBuilder fc = new StringBuilder();

        for (int i = 0; i < n; i++) {
            // Alternate zoom direction per slide for a true Ken Burns effect:
            //   Even slides: zoom IN  (100% → 115%) — crop window shrinks over time
            //   Odd  slides: zoom OUT (115% → 100%) — crop window grows over time
            boolean zoomIn = (i % 2 == 0);
            String wExpr = zoomIn
                ? scaledW + "/(1+0.15*min(t/3.72\\,1))"
                : scaledW + "/(1+0.15*(1-min(t/3.72\\,1)))";
            String hExpr = zoomIn
                ? scaledH + "/(1+0.15*min(t/3.72\\,1))"
                : scaledH + "/(1+0.15*(1-min(t/3.72\\,1)))";

            fc.append(String.format(
                "[%d:v]" +
                "scale=%d:%d:force_original_aspect_ratio=increase:flags=lanczos," +
                "crop=%d:%d:(iw-%d)/2:(ih-%d)/2," +
                "crop=w='%s':h='%s':x='(iw-ow)/2':y='(ih-oh)/2'," +
                "scale=%d:%d:flags=lanczos," +
                "format=yuv420p," +
                "setpts=PTS-STARTPTS[v%d];",
                i,
                scaledW, scaledH,
                scaledW, scaledH, scaledW, scaledH,
                wExpr, hExpr,
                WIDTH, HEIGHT,
                i
            ));
        }

        for (int i = 0; i < n; i++) fc.append("[v").append(i).append("]");
        fc.append("concat=n=").append(n).append(":v=1:a=0[slided];");

        String safe = productName != null
            ? productName.replace("'", "\\'").replace(":", "\\:").replace(",", "\\,")
            : "New Arrival";
        fc.append(String.format(
            "[slided]" +
            "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" +
            ":text='%s':fontsize=52:fontcolor=white" +
            ":x=(w-text_w)/2:y=h*0.88" +
            ":shadowcolor=black@0.8:shadowx=2:shadowy=2," +
            "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf" +
            ":text='SRISA FABRICS':fontsize=34:fontcolor=white@0.85" +
            ":x=(w-text_w)/2:y=h*0.93" +
            ":shadowcolor=black@0.6:shadowx=1:shadowy=1[outv]",
            safe
        ));

        cmd.addAll(List.of("-filter_complex", fc.toString()));
        cmd.addAll(List.of("-map", "[outv]", "-map", n + ":a"));
        cmd.addAll(List.of("-c:v", "libx264", "-preset", "faster", "-crf", "18"));
        cmd.addAll(List.of("-pix_fmt", "yuv420p"));
        cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-shortest"));
        cmd.addAll(List.of("-t", String.format("%.1f", totalSecs)));
        cmd.add(output.toAbsolutePath().toString());

        log.info("[FFmpeg] Command: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        String ffOut = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();

        if (exit != 0) {
            String tail = ffOut.length() > 2000 ? ffOut.substring(ffOut.length() - 2000) : ffOut;
            throw new RuntimeException("FFmpeg failed (exit=" + exit + "): " + tail);
        }
        log.info("[FFmpeg] Render complete: " + output + " (" + Files.size(output) / 1024 + " KB)");
    }

    private void download(HttpClient http, String url, Path dest) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .GET().build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200)
            throw new RuntimeException("Failed to download " + url + " (HTTP " + resp.statusCode() + ")");
        Files.write(dest, resp.body());
        log.info("[FFmpeg] Downloaded " + url + " → " + dest.getFileName() + " (" + resp.body().length / 1024 + " KB)");
    }

    private String fileExt(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png")) return ".png";
        if (lower.contains(".webp")) return ".webp";
        return ".jpg";
    }

    private void deleteTempDir(Path dir) {
        try {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
