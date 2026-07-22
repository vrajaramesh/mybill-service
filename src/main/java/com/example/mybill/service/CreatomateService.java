package com.example.mybill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.logging.Logger;

@Service
public class CreatomateService {

    @Value("${creatomate.api.key}")   private String apiKey;
    @Value("${creatomate.api.url}")   private String apiUrl;
    @Value("${creatomate.template.id}") private String templateId;

    private static final Logger log = Logger.getLogger(CreatomateService.class.getName());
    private static final int POLL_INTERVAL_MS = 5000;
    private static final int MAX_POLLS        = 36; // 3 min max

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest   = new RestTemplate();

    /**
     * Submits a render to Creatomate using the configured template, populates
     * Image-1 … Image-5 from the provided list, polls until done and returns the MP4 URL.
     */
    public String renderSlideshow(List<String> imageUrls) throws Exception {
        if (imageUrls.isEmpty()) throw new IllegalArgumentException("At least one image URL is required");

        ObjectNode modifications = mapper.createObjectNode();
        for (int i = 0; i < Math.min(imageUrls.size(), 5); i++) {
            modifications.put("Image-" + (i + 1), imageUrls.get(i));
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("template_id", templateId);
        payload.set("modifications", modifications);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        ResponseEntity<String> response = rest.postForEntity(
            apiUrl,
            new HttpEntity<>(mapper.writeValueAsString(payload), headers),
            String.class
        );

        JsonNode body = mapper.readTree(response.getBody());
        if (body.has("error") || !body.has("id")) {
            throw new RuntimeException("Creatomate submit failed: " + response.getBody());
        }

        String renderId = body.path("id").asText();
        log.info("[Creatomate] Render submitted: " + renderId);
        return pollForUrl(renderId);
    }

    private String pollForUrl(String renderId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        HttpEntity<Void> req = new HttpEntity<>(headers);

        for (int i = 0; i < MAX_POLLS; i++) {
            Thread.sleep(POLL_INTERVAL_MS);

            ResponseEntity<String> response = rest.exchange(
                apiUrl + "/" + renderId,
                HttpMethod.GET, req, String.class
            );

            JsonNode body = mapper.readTree(response.getBody());
            String status = body.path("status").asText();
            log.info("[Creatomate] Poll " + (i + 1) + " status=" + status);

            switch (status) {
                case "succeeded" -> { return body.path("url").asText(); }
                case "failed"    -> throw new RuntimeException(
                    "Creatomate render failed: " + body.path("error").asText("unknown error"));
            }
        }
        throw new RuntimeException("Creatomate render timed out");
    }
}
