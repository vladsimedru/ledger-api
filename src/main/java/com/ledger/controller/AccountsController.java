package com.ledger.controller;

import com.ledger.api.generated.AccountsApi;
import com.ledger.api.generated.model.AccountDto;
import com.ledger.api.generated.model.AccountEntryPageDto;
import com.ledger.api.generated.model.BalanceDto;
import com.ledger.api.generated.model.OpenAccountRequestDto;
import com.ledger.service.IdempotencyService;
import com.ledger.service.LedgerQueryService;
import com.ledger.service.LedgerService;
import java.net.URI;
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

    @Override
    public ResponseEntity<AccountDto> openAccount(String idempotencyKey, OpenAccountRequestDto request,
                                                  String xRequestId) {
        return idempotencyService.execute(idempotencyKey, request, AccountDto.class, () -> {
            AccountDto account = ledgerService.openAccount(request);
            return ResponseEntity.created(URI.create("/v1/accounts/" + account.getId())).body(account);
        });
    }

    @Override
    public ResponseEntity<AccountDto> getAccount(UUID accountId, String xRequestId) {
        return ResponseEntity.ok(ledgerQueryService.getAccount(accountId));
    }

    @Override
    public ResponseEntity<AccountEntryPageDto> listAccountEntries(UUID accountId, String xRequestId, Integer limit,
                                                                  String cursor) {
        return ResponseEntity.ok(ledgerQueryService.listEntries(accountId, cursor, limit));
    }

    @Override
    public ResponseEntity<BalanceDto> getAccountBalance(UUID accountId, String xRequestId, OffsetDateTime asOf) {
        return ResponseEntity.ok(ledgerQueryService.getBalance(accountId, asOf));
    }
}
