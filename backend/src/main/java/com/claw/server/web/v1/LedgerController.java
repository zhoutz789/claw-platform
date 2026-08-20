package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.dto.LedgerRequests;
import com.claw.server.common.dto.LedgerViews;
import com.claw.server.common.enums.AccountType;
import com.claw.server.domain.ledger.AccountService;
import com.claw.server.domain.ledger.LedgerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 账户域接口：账户查询 + 复式记账（S2）。
 *
 * <p>GET  /ledger/accounts            账户列表（?userId=&accountType=）
 * GET  /ledger/accounts/{id}         账户详情（余额/冻结）
 * POST /ledger/transactions          复式记账（借贷平衡 + bizType/bizRef 幂等）
 * GET  /ledger/transactions/{txnId}  某笔交易完整分录
 * GET  /ledger/accounts/{id}/entries 账户流水
 */
@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;
    private final AccountService accountService;

    @GetMapping("/accounts")
    public ApiResult<List<LedgerViews.AccountView>> listAccounts(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) AccountType accountType) {
        return ApiResult.ok(accountService.listAccounts(userId, accountType));
    }

    @GetMapping("/accounts/{id}")
    public ApiResult<LedgerViews.AccountView> getAccount(@PathVariable Long id) {
        return ApiResult.ok(accountService.getAccount(id));
    }

    @PostMapping("/transactions")
    public ApiResult<LedgerViews.TxnResult> postEntries(@Valid @RequestBody LedgerRequests.PostEntries req) {
        return ApiResult.ok(ledgerService.postEntries(req.bizType(), req.bizRef(), req.entries()));
    }

    @GetMapping("/transactions/{txnId}")
    public ApiResult<List<LedgerViews.EntryView>> entriesOfTxn(@PathVariable UUID txnId) {
        return ApiResult.ok(ledgerService.entriesOfTxn(txnId));
    }

    @GetMapping("/accounts/{id}/entries")
    public ApiResult<List<LedgerViews.EntryView>> entriesOfAccount(@PathVariable Long id) {
        return ApiResult.ok(ledgerService.entriesOfAccount(id));
    }
}
