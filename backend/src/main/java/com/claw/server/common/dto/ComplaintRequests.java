package com.claw.server.common.dto;

import jakarta.validation.constraints.NotBlank;

/** 投诉域请求（complaint 入参）。 */
public final class ComplaintRequests {

    private ComplaintRequests() {
    }

    /** 提交投诉（渠道：PLATFORM | CONSUMER_CENTER | NBC_HOTLINE）。 */
    public record Submit(
            String channel,
            @NotBlank String subject) {
    }

    /** 解决投诉。 */
    public record Resolve(
            @NotBlank String resolution) {
    }
}
