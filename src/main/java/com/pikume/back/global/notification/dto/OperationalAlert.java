package com.pikume.back.global.notification.dto;

import java.time.LocalDateTime;

public record OperationalAlert(
        String title,
        String environment,
        LocalDateTime occurredAt,
        String requestPath,
        String requestMethod,
        String processingStage,
        String errorType,
        int responseStatus) {
}
