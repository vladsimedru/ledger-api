package com.ledger.adapter.web.mapper;

import com.ledger.adapter.mapper.CommonMapper;
import com.ledger.api.generated.model.EntryDto;
import com.ledger.api.generated.model.EntryRequestDto;
import com.ledger.api.generated.model.RecordTransactionRequestDto;
import com.ledger.api.generated.model.TransactionDto;
import com.ledger.application.service.LedgerQueryService.TransactionView;
import com.ledger.domain.model.Entry;
import com.ledger.domain.model.EntryDraft;
import com.ledger.domain.model.LedgerCurrency;
import com.ledger.domain.model.Transaction;
import com.ledger.domain.model.TransactionDraft;
import java.util.UUID;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Transactions between the model and the API's DTOs (generated from openapi.yaml). */
@Mapper(uses = CommonMapper.class)
public interface TransactionMapper {

    // --- requests ---

    @Mapping(target = "description", source = "description")
    @Mapping(target = "entries", source = "entries")
    @Mapping(target = "reversesId", ignore = true)
    @Mapping(target = "paymentId", ignore = true)
    TransactionDraft toTransactionDraft(RecordTransactionRequestDto request);

    @Mapping(target = "accountId", source = "accountId")
    @Mapping(target = "direction", source = "direction")
    @Mapping(target = "amount", source = "amount")
    EntryDraft toEntryDraft(EntryRequestDto entry);

    // --- responses ---

    /** A transaction as read back, with the reversal that reversed it, if any. */
    default TransactionDto toTransactionDto(TransactionView view) {
        return toTransactionDto(view.transaction(), view.reversedBy(), view.transaction().currency());
    }

    /** A transaction just recorded: nothing has reversed it yet. */
    default TransactionDto toTransactionDto(Transaction transaction) {
        return toTransactionDto(transaction, null, transaction.currency());
    }

    /** @param currency the transaction's currency, which its entries' amounts are counted in */
    @Mapping(target = "id", source = "transaction.id")
    @Mapping(target = "description", source = "transaction.description")
    @Mapping(target = "currency", source = "transaction.currency")
    @Mapping(target = "recordedAt", source = "transaction.recordedAt")
    @Mapping(target = "reversesTransactionId", source = "transaction.reversesId")
    @Mapping(target = "reversedByTransactionId", source = "reversedBy")
    @Mapping(target = "paymentId", source = "transaction.paymentId")
    @Mapping(target = "entries", source = "transaction.entries")
    TransactionDto toTransactionDto(Transaction transaction, UUID reversedBy, @Context LedgerCurrency currency);

    @Mapping(target = "accountId", source = "accountId")
    @Mapping(target = "direction", source = "direction")
    @Mapping(target = "amount", source = "amount", qualifiedByName = "amount")
    EntryDto toEntryDto(Entry entry, @Context LedgerCurrency currency);
}
