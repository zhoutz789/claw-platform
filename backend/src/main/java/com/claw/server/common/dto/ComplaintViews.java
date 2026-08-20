package com.claw.server.common.dto;

import java.time.Instant;

/** 投诉域视图（complaint 出参）。 */
public final class ComplaintViews {

    private ComplaintViews() {
    }

    public record ComplaintView(
            String complaintNo, Long userId, String channel, String subject,
            String status, String resolution, Instant resolvedAt, Instant createdAt) {
    }
}
