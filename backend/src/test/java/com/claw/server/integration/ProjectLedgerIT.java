package com.claw.server.integration;

import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.domain.ledger.Account;
import com.claw.server.domain.ledger.AccountRepository;
import com.claw.server.domain.ledger.LedgerService;
import com.claw.server.domain.project.Project;
import com.claw.server.domain.project.ProjectRepository;
import com.claw.server.domain.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 项目管理域 × 真实账本集成测试（防回归 B）。
 *
 * <p>验证 {@link ProjectService#recordProjectEntry} 在<strong>真实账本</strong>下：
 * <ul>
 *   <li>INCOME：平台 MASTER 清算户余额 = 0（全新库）时不再抛 42251，借贷平衡，
 *       项目户 +amount、平台汇总户 -amount（允许负，V37 放宽 CHECK）；</li>
 *   <li>EXPENSE：项目户先有余额时正常出账，平台汇总户回正，借贷仍平衡。</li>
 * </ul>
 *
 * <p>本测试替换原 ProjectServiceTest 的 mock 路径，用真实 LedgerService 跑通端到端双记账，
 * 覆盖 QA 报告①中"开箱即坏"的隐藏 bug。
 */
class ProjectLedgerIT extends AbstractIntegrationTest {

    @Autowired ProjectService projectService;
    @Autowired LedgerService ledgerService;
    @Autowired AccountRepository accountRepository;
    @Autowired ProjectRepository projectRepository;

    @Test
    void recordProjectEntry_income_and_expense_balance_on_real_ledger() {
        // 模拟全新库：平台 MASTER 清算户余额 = 0（V5 种子仅插 account_type/currency）
        Long masterId = ledgerService.getPlatformAccountId();
        Account master = accountRepository.findById(masterId).orElseThrow();
        master.setBalance(BigDecimal.ZERO);
        accountRepository.save(master);

        // 项目专属 PROJECT 账户 + 项目行（账户 user_id 置空以规避测试库 users 外键；
        // projects.owner_user_id 无外键，用 7L 仅作归属校验）
        Account projectAccount = ledgerService.createAccount(AccountType.PROJECT, null, null);
        Project project = projectRepository.save(Project.builder()
                .ownerUserId(7L).name("IT回归项目").accountId(projectAccount.getId())
                .depth(0).sortNo(0).status("ACTIVE").build());

        // ---- INCOME：项目户 +100 / 平台汇总户 -100，不再抛 42251 ----
        BigDecimal income = new BigDecimal("100.00");
        LedgerViews.TxnResult r1 = projectService.recordProjectEntry(
                project.getId(), income, "INCOME", "光伏租金", 7L);
        assertNotNull(r1.txnId(), "INCOME 应成功入账，不应抛 42251");
        assertEquals(0, new BigDecimal("100.00").compareTo(
                accountRepository.findById(projectAccount.getId()).orElseThrow().getBalance()),
                "项目户应 +100");
        assertEquals(0, new BigDecimal("-100.00").compareTo(
                accountRepository.findById(masterId).orElseThrow().getBalance()),
                "平台汇总户应 -100（允许负，V37 放宽 CHECK）");

        // ---- EXPENSE：项目户 -30 / 平台汇总户 +30，借贷仍平衡 ----
        BigDecimal expense = new BigDecimal("30.00");
        LedgerViews.TxnResult r2 = projectService.recordProjectEntry(
                project.getId(), expense, "EXPENSE", "运维支出", 7L);
        assertNotNull(r2.txnId(), "EXPENSE 应成功入账");
        assertEquals(0, new BigDecimal("70.00").compareTo(
                accountRepository.findById(projectAccount.getId()).orElseThrow().getBalance()),
                "项目户应 70");
        assertEquals(0, new BigDecimal("-70.00").compareTo(
                accountRepository.findById(masterId).orElseThrow().getBalance()),
                "平台汇总户应 -70");
    }
}
