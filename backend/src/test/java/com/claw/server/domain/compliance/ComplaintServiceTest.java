package com.claw.server.domain.compliance;

import com.claw.server.common.dto.ComplaintRequests;
import com.claw.server.common.dto.ComplaintViews;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 投诉域单元测试：受理、渠道校验、解决、状态机拒绝。
 */
@ExtendWith(MockitoExtension.class)
class ComplaintServiceTest {

    @Mock private ComplaintRepository complaintRepository;
    @InjectMocks private ComplaintService service;

    @Test
    void submit_creates_received_complaint() {
        when(complaintRepository.save(any(Complaint.class))).thenAnswer(inv -> inv.getArgument(0));

        ComplaintViews.ComplaintView v = service.submit(100L,
                new ComplaintRequests.Submit("CONSUMER_CENTER", "押金未退回"));

        assertEquals("RECEIVED", v.status());
        assertEquals("CONSUMER_CENTER", v.channel());
        assertNotNull(v.complaintNo());
    }

    @Test
    void submit_defaults_to_platform_channel() {
        when(complaintRepository.save(any(Complaint.class))).thenAnswer(inv -> inv.getArgument(0));

        ComplaintViews.ComplaintView v = service.submit(100L, new ComplaintRequests.Submit(null, "服务态度差"));

        assertEquals("PLATFORM", v.channel());
    }

    @Test
    void submit_rejects_invalid_channel() {
        assertThrows(Exception.class, () -> service.submit(100L,
                new ComplaintRequests.Submit("WHATSAPP", "x")));
        verify(complaintRepository, never()).save(any());
    }

    @Test
    void resolve_marks_resolved() {
        Complaint c = Complaint.builder().complaintNo("CP-1").userId(100L)
                .channel("PLATFORM").subject("s").status("RECEIVED").build();
        when(complaintRepository.findByComplaintNo("CP-1")).thenReturn(Optional.of(c));
        when(complaintRepository.save(any(Complaint.class))).thenAnswer(inv -> inv.getArgument(0));

        ComplaintViews.ComplaintView v = service.resolve("CP-1", "已退回押金", 200L);

        assertEquals("RESOLVED", v.status());
        assertEquals("已退回押金", v.resolution());
        assertEquals(200L, c.getResolvedBy());
    }

    @Test
    void resolve_rejects_when_already_resolved() {
        Complaint c = Complaint.builder().complaintNo("CP-1").userId(100L)
                .channel("PLATFORM").subject("s").status("RESOLVED").build();
        when(complaintRepository.findByComplaintNo("CP-1")).thenReturn(Optional.of(c));

        assertThrows(Exception.class, () -> service.resolve("CP-1", "x", 200L));
    }
}
