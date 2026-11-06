package com.ledger.adapter.web.controller;

import com.ledger.adapter.web.idempotency.IdempotencyService;
import com.ledger.adapter.web.mapper.AccountMapper;
import com.ledger.api.generated.AccountsApi;
import com.ledger.api.generated.model.AccountDto;
import com.ledger.api.generated.model.AccountEntryPageDto;
import com.ledger.api.generated.model.BalanceDto;
import com.ledger.api.generated.model.OpenAccountRequestDto;
import com.ledger.application.service.LedgerQueryService.EntryPage;
import com.ledger.application.service.LedgerQueryService;
import com.ledger.application.service.LedgerService;
import com.ledger.domain.model.Account;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class AccountsController implements AccountsApi {

    private final LedgerService ledgerService;
    private final LedgerQueryService ledgerQueryService;
    private final IdempotencyService idempotencyService;
    private final AccountMapper accountMapper;

    @Override
    public ResponseEntity<AccountDto> openAccount(String idempotencyKey, OpenAccountRequestDto request,
                                                  String xRequestId) {
        return idempotencyService.execute(idempotencyKey, request, AccountDto.class, () -> {
            Account account = ledgerService.openAccount(request.getCurrency(),
                    accountMapper.toRequestedOverdraftLimit(request), request.getLabel());
            return ResponseEntity.created(URI.create("/v1/accounts/" + account.id()))
                    .body(accountMapper.toAccountDto(account));
        });
    }

    @Override
    public ResponseEntity<AccountDto> getAccount(UUID accountId, String xRequestId) {
        return ResponseEntity.ok(accountMapper.toAccountDto(ledgerQueryService.getAccount(accountId)));
    }

    @Override
    public ResponseEntity<AccountEntryPageDto> listAccountEntries(UUID accountId, String xRequestId, Integer limit,
                                                                  String cursor) {
        Long beforeEntryId = accountMapper.decodeCursor(cursor);
        EntryPage page = ledgerQueryService.listEntries(accountId, beforeEntryId, limit == null ? 50 : limit);
        return ResponseEntity.ok(accountMapper.toAccountEntryPageDto(page));
    }

    @Override
    public ResponseEntity<BalanceDto> getAccountBalance(UUID accountId, String xRequestId, OffsetDateTime asOf) {
        Instant at = asOf == null ? null : asOf.toInstant();
        return ResponseEntity.ok(accountMapper.toBalanceDto(ledgerQueryService.getBalance(accountId, at)));
    }
}
