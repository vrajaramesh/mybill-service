package com.example.mybill.boutique.dto;

import java.util.Map;

/** Error body for every /api/agent/** failure (also written by AgentAuthFilter for 401/403/503). */
public record AgentError(String code, String message, Map<String, Object> details) {}
