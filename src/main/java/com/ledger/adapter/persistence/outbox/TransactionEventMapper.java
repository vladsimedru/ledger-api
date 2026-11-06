package com.ledger.adapter.persistence.outbox;

import com.ledger.adapter.mapper.CommonMapper;
import com.ledger.domain.model.Entry;
import com.ledger.domain.model.LedgerCurrency;
import com.ledger.domain.model.Transaction;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** A recorded transaction as the body of its announcement. Ids and amounts are strings, as in the API. */
@Mapper(uses = CommonMapper.class)
public interface TransactionEventMapper {

    default TransactionRecordedEvent.Body toEventBody(Transaction transaction) {
        return toEventBody(transaction, transaction.currency());
    }

    /** @param currency the transaction's currency, which its entries' amounts are counted in */
    @Mapping(target = "id", source = "id")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "recordedAt", source = "recordedAt")
    @Mapping(target = "reversesTransactionId", source = "reversesId")
    @Mapping(target = "paymentId", source = "paymentId")
    @Mapping(target = "entries", source = "entries")
    TransactionRecordedEvent.Body toEventBody(Transaction transaction, @Context LedgerCurrency currency);

    @Mapping(target = "accountId", source = "accountId")
    @Mapping(target = "direction", source = "direction")
    @Mapping(target = "amount", source = "amount", qualifiedByName = "amount")
    @Mapping(target = "balanceAfter", source = "balanceAfter", qualifiedByName = "amount")
    TransactionRecordedEvent.Line toEventLine(Entry entry, @Context LedgerCurrency currency);
}
