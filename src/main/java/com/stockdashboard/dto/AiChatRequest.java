package com.stockdashboard.dto;

import java.util.List;

public record AiChatRequest(List<AiChatMessage> history, String message) {
}
