package com.example.mybill.service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@Profile("!local")
public class GcsUploadService implements ImageUploadService {

    @Value("${gcs.bucket-name}")
    private String bucketName;

    private final Storage storage = StorageOptions.getDefaultInstance().getService();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String uploadBase64(String base64, String mimeType) {
        try {
            String mime = mimeType != null ? mimeType : "image/jpeg";
            String ext = mime.contains("png") ? "png" : "jpg";
            String objectName = "ai-generated/" + UUID.randomUUID() + "." + ext;

            byte[] bytes = Base64.getDecoder().decode(base64);
            BlobId blobId = BlobId.of(bucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(mime).build();
            storage.create(blobInfo, bytes);

            return "https://storage.googleapis.com/" + bucketName + "/" + objectName;
        } catch (Exception e) {
            throw new RuntimeException("GCS base64 upload error: " + e.getMessage(), e);
        }
    }

    @Override
    public String uploadFile(byte[] bytes, String mimeType) {
        try {
            String mime = mimeType != null ? mimeType : "image/jpeg";
            String ext = mime.contains("png") ? "png" : "jpg";
            String objectName = "product-uploads/" + UUID.randomUUID() + "." + ext;
            BlobId blobId = BlobId.of(bucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(mime).build();
            storage.create(blobInfo, bytes);
            return "https://storage.googleapis.com/" + bucketName + "/" + objectName;
        } catch (Exception e) {
            throw new RuntimeException("GCS file upload error: " + e.getMessage(), e);
        }
    }

    /** An object in the bucket: its name (e.g. dropship/KSS1211/collage.jpg) and when it was written. */
    public record StoredObject(String name, Instant created) {}

    /** Every object whose name starts with prefix. */
    public List<StoredObject> list(String prefix) {
        List<StoredObject> out = new ArrayList<>();
        for (var blob : storage.list(bucketName, Storage.BlobListOption.prefix(prefix)).iterateAll()) {
            out.add(new StoredObject(blob.getName(), blob.getCreateTimeOffsetDateTime() == null ? Instant.EPOCH
                : blob.getCreateTimeOffsetDateTime().toInstant()));
        }
        return out;
    }

    /** Delete one object; false if it was already gone. */
    public boolean delete(String objectName) {
        return storage.delete(BlobId.of(bucketName, objectName));
    }

    /** The object name of one of our public URLs (https://storage.googleapis.com/{bucket}/{name}); null for any other URL. */
    public String objectName(String url) {
        String base = "https://storage.googleapis.com/" + bucketName + "/";
        return url != null && url.startsWith(base) ? url.substring(base.length()) : null;
    }

    /** Upload to a chosen object name (e.g. dropship/12/original/3.jpg); returns the public URL. Overwrites. */
    public String uploadToPath(byte[] bytes, String mimeType, String objectName) {
        try {
            BlobId blobId = BlobId.of(bucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(mimeType != null ? mimeType : "image/jpeg").build();
            storage.create(blobInfo, bytes);
            return "https://storage.googleapis.com/" + bucketName + "/" + objectName;
        } catch (Exception e) {
            throw new RuntimeException("GCS upload error: " + e.getMessage(), e);
        }
    }

    public String uploadVideo(byte[] bytes) {
        try {
            String objectName = "reels/" + UUID.randomUUID() + ".mp4";
            BlobId blobId = BlobId.of(bucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("video/mp4").build();
            storage.create(blobInfo, bytes);
            return "https://storage.googleapis.com/" + bucketName + "/" + objectName;
        } catch (Exception e) {
            throw new RuntimeException("GCS video upload error: " + e.getMessage(), e);
        }
    }

    @Override
    public String uploadFromUrl(String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200)
                throw new RuntimeException("Failed to download image (" + response.statusCode() + ")");

            String contentType = response.headers().firstValue("Content-Type").orElse("image/png");
            String ext = contentType.contains("jpeg") || contentType.contains("jpg") ? "jpg" : "png";
            String objectName = "ai-generated/" + UUID.randomUUID() + "." + ext;

            BlobId blobId = BlobId.of(bucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(contentType).build();
            storage.create(blobInfo, response.body());

            return "https://storage.googleapis.com/" + bucketName + "/" + objectName;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GCS upload error: " + e.getMessage(), e);
        }
    }
}