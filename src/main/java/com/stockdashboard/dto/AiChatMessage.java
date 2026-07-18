package com.stockdashboard.dto;

/** One turn in an AI chat conversation. role is "user" or "model" (Gemini's own role vocabulary). */
public record AiChatMessage(String role, String content) {
}
