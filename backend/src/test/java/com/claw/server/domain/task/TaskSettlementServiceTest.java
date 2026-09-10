package com.claw.server.domain.task;

import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.enums.BizType;
import com.claw.server.common.enums.TaskStatus;
import com.claw.server.domain.asset.AssetRepository;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountEntry;
import com.claw.server.domain.ledger.AccountEntryRepository;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskSettlementService 闭环单元测试（无 DB，Mockito）。
 * 验证 settle() 经 LedgerService.postEntries 提交恰好 2 条平衡分录：
 * publisher D reward / provider C reward，且 bizRef = "TASK-<taskId>-<assignmentId>"。
 */
@ExtendWith(MockitoExtension.class)
class TaskSettlementServiceTest {

    @Mock
    private AccountService accountService;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TaskAssignmentRepository taskAssignmentRepository;
    @Mock
    private AccountEntryRepository accountEntryRepository;
    @Mock
    private AssetRepository assetRepository;

    @InjectMocks
    private TaskSettlementService settlementService;

    @Test
    void settle_postsDoubleEntryForLogisticsClosedLoop() {
        Long publisherId = 1L;
        Long providerId = 2L;
        Long taskId = 7L;
        Long assignmentId = 5L;

        Account publisherAcct = Account.builder().id(10L).userId(publisherId).build();
        Account providerAcct = Account.builder().id(20L).userId(providerId).build();
        when(accountService.getOrCreateUserAccount(publisherId)).thenReturn(publisherAcct);
        when(accountService.getOrCreateUserAccount(providerId)).thenReturn(providerAcct);

        Task task = Task.builder()
                .id(taskId)
                .publisherId(publisherId)
                .rewardAmount(new BigDecimal("5.00"))
                .status(TaskStatus.COMPLETED)
                .build();
        TaskAssignment assignment = TaskAssignment.builder()
                .id(assignmentId)
                .taskId(taskId)
                .providerId(providerId)
                .assetId(99L)
                .status(TaskStatus.COMPLETED)
                .build();

        settlementService.settle(task, assignment);

        ArgumentCaptor<BizType> bizTypeCaptor = ArgumentCaptor.forClass(BizType.class);
        ArgumentCaptor<String> bizRefCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerRequests.Entry>> entriesCaptor =
                (ArgumentCaptor<List<LedgerRequests.Entry>>) (Object) ArgumentCaptor.forClass(List.class);

        verify(ledgerService, times(1))
                .postEntries(bizTypeCaptor.capture(), bizRefCaptor.capture(), entriesCaptor.capture());

        assertEquals(BizType.TASK_SETTLEMENT, bizTypeCaptor.getValue());
        assertEquals("TASK-7-5", bizRefCaptor.getValue());

        List<LedgerRequests.Entry> entries = entriesCaptor.getValue();
        assertEquals(2, entries.size());

        LedgerRequests.Entry debit = entries.get(0);
        assertEquals(LedgerRequests.Direction.D, debit.direction());
        assertEquals(0, new BigDecimal("5.00").compareTo(debit.amount()));
        assertEquals(10L, debit.accountId());

        LedgerRequests.Entry credit = entries.get(1);
        assertEquals(LedgerRequests.Direction.C, credit.direction());
        assertEquals(0, new BigDecimal("5.00").compareTo(credit.amount()));
        assertEquals(20L, credit.accountId());

        // 服务内同步将任务置为 SETTLED
        assertEquals(TaskStatus.SETTLED, task.getStatus());
    }
}
