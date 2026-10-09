package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.dto.WholesaleInstagramSettingsRequest;
import com.example.mybill.wholesale.dto.WholesaleInstagramSettingsResponse;
import com.example.mybill.wholesale.entity.WholesaleInstagramSettings;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleInstagramSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Instagram messaging connection settings (single row). Secrets are write-only. */
@Service
public class WholesaleInstagramSettingsService {

    /** The token is sent to this host, so only Meta's Graph API hosts are accepted. */
    private static final String[] ALLOWED_API_HOSTS = {"https://graph.facebook.com/", "https://graph.instagram.com/"};

    @Autowired private WholesaleInstagramSettingsRepository repository;

    @Autowired private WholesaleLlmClient llm;

    /** The stored row (created by V11); used by the messaging service. */
    @Transactional(readOnly = true)
    public WholesaleInstagramSettings current() {
        return repository.findById(WholesaleInstagramSettings.SINGLETON_ID).orElseGet(WholesaleInstagramSettings::new);
    }

    @Transactional(readOnly = true)
    public WholesaleInstagramSettingsResponse get(String firmCode) {
        return toResponse(current(), firmCode);
    }

    @Transactional
    public WholesaleInstagramSettingsResponse save(WholesaleInstagramSettingsRequest r, String username, String firmCode) {
        WholesaleInstagramSettings s = repository.findById(WholesaleInstagramSettings.SINGLETON_ID).orElseGet(WholesaleInstagramSettings::new);
        String base = blankToNull(r.apiBaseUrl());
        if (base != null) {
            boolean allowed = false;
            for (String host : ALLOWED_API_HOSTS) allowed |= base.startsWith(host);
            if (!allowed) throw WholesaleException.badRequest("API base URL must start with https://graph.facebook.com/ or https://graph.instagram.com/");
            s.setApiBaseUrl(base);
        }
        if (r.isEnabled() != null) s.setIsEnabled(r.isEnabled());
        if (r.autoReplyEnabled() != null) s.setAutoReplyEnabled(r.autoReplyEnabled());
        s.setInstagramAccountId(blankToNull(r.instagramAccountId()));
        s.setVerifyToken(blankToNull(r.verifyToken()));
        if (r.accessToken() != null) s.setAccessToken(blankToNull(r.accessToken()));
        if (r.appSecret() != null) s.setAppSecret(blankToNull(r.appSecret()));
        s.setHandoffMessage(r.handoffMessage().trim());

        if (Boolean.TRUE.equals(s.getIsEnabled())) {
            if (s.getVerifyToken() == null) throw WholesaleException.badRequest("Verify token is required to enable Instagram messaging");
            if (s.getAppSecret() == null) throw WholesaleException.badRequest("App secret is required to enable Instagram messaging (webhook signatures are checked with it)");
            if (s.getAccessToken() == null) throw WholesaleException.badRequest("Access token is required to enable Instagram messaging");
        }
        s.setUpdatedBy(username);
        return toResponse(repository.save(s), firmCode);
    }

    private WholesaleInstagramSettingsResponse toResponse(WholesaleInstagramSettings s, String firmCode) {
        return new WholesaleInstagramSettingsResponse(
            s.getIsEnabled(), s.getAutoReplyEnabled(), s.getInstagramAccountId(), s.getApiBaseUrl(),
            s.getAccessToken() != null && !s.getAccessToken().isBlank(), s.getAppSecret() != null && !s.getAppSecret().isBlank(),
            s.getVerifyToken(), s.getHandoffMessage(),
            firmCode == null ? null : "/api/public/" + firmCode + "/wholesale/instagram/webhook",
            llm.isConfigured(), s.getUpdatedAt(), s.getUpdatedBy());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
