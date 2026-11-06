package com.ledger.mapper;

import com.ledger.entity.Entry;
import com.ledger.entity.Transaction;
import com.ledger.messaging.EntryPayload;
import com.ledger.messaging.TransactionPayload;
import com.ledger.model.LedgerCurrency;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** A recorded transaction as the payload of its announcement ({@link TransactionPayload}). Ids and amounts are strings, as in the API. */
@Mapper(uses = CommonMapper.class)
public interface TransactionEventMapper {

    default TransactionPayload toTransactionPayload(Transaction transaction) {
        return toTransactionPayload(transaction, transaction.currency());
    }

    /** @param currency the transaction's currency, which its entries' amounts are counted in */
    @Mapping(target = "id", source = "id")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "recordedAt", source = "recordedAt")
    @Mapping(target = "reversesTransactionId", source = "reversesId")
    @Mapping(target = "paymentId", source = "paymentId")
    @Mapping(target = "entries", source = "entries")
    TransactionPayload toTransactionPayload(Transaction transaction, @Context LedgerCurrency currency);

    @Mapping(target = "accountId", source = "accountId")
    @Mapping(target = "direction", source = "direction")
    @Mapping(target = "amount", source = "amount", qualifiedByName = "amount")
    @Mapping(target = "balanceAfter", source = "balanceAfter", qualifiedByName = "amount")
    EntryPayload toEntryPayload(Entry entry, @Context LedgerCurrency currency);
}
