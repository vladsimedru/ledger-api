package com.ledger.adapter.web.controller;

import com.ledger.adapter.web.idempotency.IdempotencyService;
import com.ledger.adapter.web.mapper.TransactionMapper;
import com.ledger.api.generated.TransactionsApi;
import com.ledger.api.generated.model.RecordTransactionRequestDto;
import com.ledger.api.generated.model.ReverseTransactionRequestDto;
import com.ledger.api.generated.model.TransactionDto;
import com.ledger.application.service.LedgerQueryService.TransactionView;
import com.ledger.application.service.LedgerQueryService;
import com.ledger.application.service.LedgerService;
import com.ledger.domain.model.Transaction;
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
    private final TransactionMapper transactionMapper;

    @Override
    public ResponseEntity<TransactionDto> recordTransaction(String idempotencyKey, RecordTransactionRequestDto request,
                                                            String xRequestId) {
        return idempotencyService.execute(idempotencyKey, request, TransactionDto.class, () -> {
            Transaction t = ledgerService.recordTransaction(transactionMapper.toTransactionDraft(request));
            return ResponseEntity.created(URI.create("/v1/transactions/" + t.id()))
                    .body(transactionMapper.toTransactionDto(t));
        });
    }

    @Override
    public ResponseEntity<TransactionDto> getTransaction(UUID transactionId, String xRequestId) {
        TransactionView view = ledgerQueryService.getTransaction(transactionId);
        return ResponseEntity.ok(transactionMapper.toTransactionDto(view));
    }

    @Override
    public ResponseEntity<TransactionDto> reverseTransaction(UUID transactionId, String idempotencyKey,
                                                             String xRequestId, ReverseTransactionRequestDto request) {
        return idempotencyService.execute(idempotencyKey, request, TransactionDto.class, () -> {
            String description = request == null ? null : request.getDescription();
            Transaction t = ledgerService.reverseTransaction(transactionId, description);
            return ResponseEntity.created(URI.create("/v1/transactions/" + t.id()))
                    .body(transactionMapper.toTransactionDto(t));
        });
    }
}
