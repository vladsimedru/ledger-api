package com.ledger.adapter.web.mapper;

import com.ledger.adapter.mapper.CommonMapper;
import com.ledger.adapter.web.exception.ApiErrorCode;
import com.ledger.adapter.web.exception.BadRequestException;
import com.ledger.api.generated.model.AccountDto;
import com.ledger.api.generated.model.AccountEntryDto;
import com.ledger.api.generated.model.AccountEntryPageDto;
import com.ledger.api.generated.model.BalanceDto;
import com.ledger.api.generated.model.OpenAccountRequestDto;
import com.ledger.application.port.LedgerReadRepository.AccountEntryView;
import com.ledger.application.service.LedgerQueryService.BalanceView;
import com.ledger.application.service.LedgerQueryService.EntryPage;
import com.ledger.domain.model.Account;
import com.ledger.domain.model.Money;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Base64;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/** Accounts, their entries and balances, between the model and the API's DTOs (generated from openapi.yaml). */
@Mapper(uses = CommonMapper.class)
public interface AccountMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "overdraftLimit", source = "account", qualifiedByName = "overdraftLimit")
    @Mapping(target = "balance", source = "account", qualifiedByName = "balance")
    @Mapping(target = "label", source = "label")
    @Mapping(target = "createdAt", source = "createdAt")
    AccountDto toAccountDto(Account account);

    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "currency", source = "account.currency")
    @Mapping(target = "balance", source = "balance")
    @Mapping(target = "asOf", source = "asOf")
    BalanceDto toBalanceDto(BalanceView balance);

    @Mapping(target = "items", source = "items")
    @Mapping(target = "nextCursor", source = "nextCursor", qualifiedByName = "encodeCursor")
    AccountEntryPageDto toAccountEntryPageDto(EntryPage page);

    @Mapping(target = "transactionId", source = "transactionId")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "direction", source = "direction")
    @Mapping(target = "amount", source = "amount")
    @Mapping(target = "balanceAfter", source = "balanceAfter")
    @Mapping(target = "recordedAt", source = "recordedAt")
    AccountEntryDto toAccountEntryDto(AccountEntryView entry);

    /** The overdraft limit an account is opened with; {@code null} for the default of zero. */
    default BigDecimal toRequestedOverdraftLimit(OpenAccountRequestDto request) {
        return request.getOverdraftLimit() == null ? null : new BigDecimal(request.getOverdraftLimit());
    }

    @Named("balance")
    default String toBalance(Account account) {
        return new Money(account.balance(), account.currency()).toPlainString();
    }

    /** Absent for the ledger's own accounts, which have no limit. */
    @Named("overdraftLimit")
    default String toOverdraftLimit(Account account) {
        return account.overdraftLimit() == null ? null
                : new Money(account.overdraftLimit(), account.currency()).toPlainString();
    }

    // --- paging cursors: opaque to clients, the id of the last entry of the previous page ---

    @Named("encodeCursor")
    default String encodeCursor(Long entryId) {
        return entryId == null ? null
                : Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(8).putLong(entryId).array());
    }

    /** @throws BadRequestException if the cursor isn't one this API issued */
    @Named("decodeCursor")
    default Long decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            if (bytes.length == 8) {
                return ByteBuffer.wrap(bytes).getLong();
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        throw new BadRequestException(ApiErrorCode.MALFORMED_REQUEST, null,
                "'" + cursor + "' is not a cursor this API issued");
    }
}
