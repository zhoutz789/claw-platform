package com.claw.server.domain.compliance;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ComplaintRequests;
import com.claw.server.common.dto.ComplaintViews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 金融消费者投诉服务（S5）：受理 / 解决。
 *
 * <p>技术文档合规域：投诉渠道 = 平台 | 金融消费者中心 | NBC 热线；
 * 状态机 RECEIVED → PROCESSING → RESOLVED（对接中心/NBC 热线处理）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintService {

    private static final Set<String> CHANNELS = Set.of("PLATFORM", "CONSUMER_CENTER", "NBC_HOTLINE");
    private static final Set<String> RESOLVABLE = Set.of("RECEIVED", "PROCESSING");

    private final ComplaintRepository complaintRepository;

    /** 提交投诉（受理）。 */
    @Transactional
    public ComplaintViews.ComplaintView submit(Long userId, ComplaintRequests.Submit req) {
        String channel = req.channel() == null || req.channel().isBlank()
                ? "PLATFORM" : req.channel().toUpperCase();
        if (!CHANNELS.contains(channel)) {
            throw BizException.invalidParam("error.complaint.channel.invalid");
        }
        Complaint c = complaintRepository.save(Complaint.builder()
                .complaintNo("CP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .userId(userId).channel(channel).subject(req.subject())
                .status("RECEIVED").build());
        log.info("投诉受理 {} 渠道={} 用户={}", c.getComplaintNo(), channel, userId);
        return toView(c);
    }

    /** 解决投诉（RECEIVED/PROCESSING → RESOLVED）。 */
    @Transactional
    public ComplaintViews.ComplaintView resolve(String complaintNo, String resolution, Long operatorId) {
        Complaint c = complaintRepository.findByComplaintNo(complaintNo)
                .orElseThrow(() -> BizException.notFound("error.complaint.not.found"));
        if (!RESOLVABLE.contains(c.getStatus())) {
            throw BizException.of(40980, "error.complaint.status");
        }
        c.setStatus("RESOLVED");
        c.setResolution(resolution);
        c.setResolvedBy(operatorId);
        c.setResolvedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        complaintRepository.save(c);
        log.info("投诉解决 {} by {}", complaintNo, operatorId);
        return toView(c);
    }

    /** 用户投诉列表。 */
    @Transactional(readOnly = true)
    public List<ComplaintViews.ComplaintView> listByUser(Long userId) {
        return complaintRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toView).toList();
    }

    /** 按状态查询（后台）。 */
    @Transactional(readOnly = true)
    public List<ComplaintViews.ComplaintView> listByStatus(String status) {
        return complaintRepository.findByStatusOrderByCreatedAtDesc(status).stream()
                .map(this::toView).toList();
    }

    private ComplaintViews.ComplaintView toView(Complaint c) {
        return new ComplaintViews.ComplaintView(c.getComplaintNo(), c.getUserId(), c.getChannel(),
                c.getSubject(), c.getStatus(), c.getResolution(), c.getResolvedAt(), c.getCreatedAt());
    }
}
