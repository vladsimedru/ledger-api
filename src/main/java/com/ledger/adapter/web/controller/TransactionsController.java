package com.ledger.adapter.web.controller;

import com.ledger.adapter.web.idempotency.IdempotencyService;
import com.ledger.api.generated.TransactionsApi;
import com.ledger.api.generated.model.RecordTransactionRequestDto;
import com.ledger.api.generated.model.ReverseTransactionRequestDto;
import com.ledger.api.generated.model.TransactionDto;
import com.ledger.application.service.LedgerQueryService;
import com.ledger.application.service.LedgerService;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class TransactionsController implements TransactionsApi {

    private final LedgerService ledgerService;
    private final LedgerQueryService ledgerQueryService;
    private final IdempotencyService idempotencyService;

    @Override
    public ResponseEntity<TransactionDto> recordTransaction(String idempotencyKey, RecordTransactionRequestDto request,
                                                            String xRequestId) {
        return idempotencyService.execute(idempotencyKey, request, TransactionDto.class, () -> {
            TransactionDto transaction = ledgerService.recordTransaction(request);
            return ResponseEntity.created(URI.create("/v1/transactions/" + transaction.getId())).body(transaction);
        });
    }

    @Override
    public ResponseEntity<TransactionDto> getTransaction(UUID transactionId, String xRequestId) {
        return ResponseEntity.ok(ledgerQueryService.getTransaction(transactionId));
    }

    @Override
    public ResponseEntity<TransactionDto> reverseTransaction(UUID transactionId, String idempotencyKey,
                                                             String xRequestId, ReverseTransactionRequestDto request) {
        return idempotencyService.execute(idempotencyKey, request, TransactionDto.class, () -> {
            TransactionDto reversal = ledgerService.reverseTransaction(transactionId, request);
            return ResponseEntity.created(URI.create("/v1/transactions/" + reversal.getId())).body(reversal);
        });
    }
}
