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
import java.util.Base64;
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
            String objectName = "chat-images/" + UUID.randomUUID() + "." + ext;

            byte[] bytes = Base64.getDecoder().decode(base64);
            BlobId blobId = BlobId.of(bucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(mime).build();
            storage.create(blobInfo, bytes, Storage.BlobTargetOption.predefinedAcl(Storage.PredefinedAcl.PUBLIC_READ));

            return "https://storage.googleapis.com/" + bucketName + "/" + objectName;
        } catch (Exception e) {
            throw new RuntimeException("GCS base64 upload error: " + e.getMessage(), e);
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
            storage.create(blobInfo, response.body(), Storage.BlobTargetOption.predefinedAcl(Storage.PredefinedAcl.PUBLIC_READ));

            return "https://storage.googleapis.com/" + bucketName + "/" + objectName;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GCS upload error: " + e.getMessage(), e);
        }
    }
}