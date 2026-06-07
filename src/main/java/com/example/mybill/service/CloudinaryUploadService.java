package com.example.mybill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
@Profile("local")
public class CloudinaryUploadService implements ImageUploadService {

    @Value("${cloudinary.cloud-name}")
    private String cloudName;

    @Value("${cloudinary.upload-preset}")
    private String uploadPreset;

    @Value("${cloudinary.api.url}")
    private String cloudinaryApiUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String uploadBase64(String base64, String mimeType) {
        try {
            byte[] imageBytes = java.util.Base64.getDecoder().decode(base64);
            String mime = mimeType != null ? mimeType : "image/jpeg";
            String ext  = mime.contains("png") ? "png" : "jpg";
            System.err.println("[CLOUDINARY] uploadBase64: " + kb(imageBytes.length) + " KB, mime=" + mime);
            return uploadBytes(imageBytes, mime, ext, "ai-generated");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Cloudinary base64 upload error: " + e.getMessage(), e);
        }
    }

    private String uploadBytes(byte[] imageBytes, String mimeType, String ext, String folder) throws Exception {
        String boundary = "----CloudinaryBoundary" + Long.toHexString(System.currentTimeMillis());
        byte[] body = buildMultipart(boundary, "ai-generated." + ext, mimeType, imageBytes, folder);

        System.err.println("[CLOUDINARY] Uploading multipart: " + kb(body.length) + " KB to "
            + cloudinaryApiUrl + "/" + cloudName + "/image/upload");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(cloudinaryApiUrl + "/" + cloudName + "/image/upload"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .timeout(Duration.ofSeconds(90))
            .build();

        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        System.err.println("[CLOUDINARY] Response: status=" + response.statusCode()
            + " elapsed=" + (System.currentTimeMillis() - t0) + "ms");

        if (response.statusCode() != 200)
            throw new RuntimeException("Upload failed (" + response.statusCode() + "): " + response.body());

        String url = objectMapper.readTree(response.body()).path("secure_url").asText();
        System.err.println("[CLOUDINARY] Uploaded: " + url);
        return url;
    }

    private byte[] buildMultipart(String boundary, String filename, String mimeType,
                                   byte[] fileBytes, String folder) throws Exception {
        String CRLF = "\r\n";
        String delimiter = "--" + boundary + CRLF;
        String close     = "--" + boundary + "--" + CRLF;

        StringBuilder header = new StringBuilder();
        header.append(delimiter)
              .append("Content-Disposition: form-data; name=\"upload_preset\"").append(CRLF).append(CRLF)
              .append(uploadPreset).append(CRLF)
              .append(delimiter)
              .append("Content-Disposition: form-data; name=\"folder\"").append(CRLF).append(CRLF)
              .append(folder).append(CRLF)
              .append(delimiter)
              .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"").append(CRLF)
              .append("Content-Type: ").append(mimeType).append(CRLF).append(CRLF);

        byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = (CRLF + close).getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(fileBytes,   0, result, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, result, headerBytes.length + fileBytes.length, footerBytes.length);
        return result;
    }

    @Override
    public String uploadFile(byte[] bytes, String mimeType) {
        try {
            String mime = mimeType != null ? mimeType : "image/jpeg";
            String ext  = mime.contains("png") ? "png" : "jpg";
            return uploadBytes(bytes, mime, ext, "product-uploads");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Cloudinary file upload error: " + e.getMessage(), e);
        }
    }

    @Override
    public String uploadFromUrl(String imageUrl) {
        try {
            String body = "file=" + URLEncoder.encode(imageUrl, StandardCharsets.UTF_8) +
                          "&upload_preset=" + URLEncoder.encode(uploadPreset, StandardCharsets.UTF_8) +
                          "&folder=ai-generated";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cloudinaryApiUrl + "/" + cloudName + "/image/upload"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();

            long t0 = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.err.println("[CLOUDINARY] uploadFromUrl: status=" + response.statusCode()
                + " elapsed=" + (System.currentTimeMillis() - t0) + "ms");

            if (response.statusCode() != 200)
                throw new RuntimeException("Cloudinary upload failed (" + response.statusCode() + "): " + response.body());

            JsonNode json = objectMapper.readTree(response.body());
            return json.path("secure_url").asText();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Cloudinary upload error: " + e.getMessage(), e);
        }
    }

    private static String kb(long bytes) {
        return String.format("%.1f", bytes / 1024.0);
    }
}
