package com.ledger.mapper;

import static com.ledger.model.LedgerCurrency.EUR;
import static com.ledger.model.LedgerCurrency.JPY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledger.api.generated.model.AccountDto;
import com.ledger.api.generated.model.AccountEntryDto;
import com.ledger.api.generated.model.AccountEntryPageDto;
import com.ledger.api.generated.model.BalanceDto;
import com.ledger.api.generated.model.DirectionDto;
import com.ledger.api.generated.model.OpenAccountRequestDto;
import com.ledger.entity.Account;
import com.ledger.exception.ApiErrorCode;
import com.ledger.exception.BadRequestException;
import com.ledger.model.AccountEntryView;
import com.ledger.model.BalanceView;
import com.ledger.model.Direction;
import com.ledger.model.EntryPage;
import com.ledger.model.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Only the generated mappers in the context: no database, no web server. */
@SpringJUnitConfig({AccountMapperImpl.class, CommonMapperImpl.class})
class AccountMapperTest {

    private static final UUID ID = UUID.fromString("01960000-0000-7000-8000-000000000042");
    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.parse("2026-11-04T10:00:00Z");

    @Autowired
    AccountMapper accountMapper;

    @Test
    void an_account_has_its_amounts_as_decimal_strings_in_its_currency() {
        AccountDto dto = accountMapper.toAccountDto(new Account(ID, EUR, 10000L, -2450, "Merchant 42", NOW));

        assertThat(dto.getId()).isEqualTo(ID);
        assertThat(dto.getCurrency()).isEqualTo("EUR");
        assertThat(dto.getBalance()).isEqualTo("-24.50");
        assertThat(dto.getOverdraftLimit()).isEqualTo("100.00");
        assertThat(dto.getLabel()).isEqualTo("Merchant 42");
        assertThat(dto.getCreatedAt()).isEqualTo(NOW_UTC);
    }

    @Test
    void jpy_amounts_have_no_decimal_places() {
        AccountDto dto = accountMapper.toAccountDto(new Account(ID, JPY, 0L, 15000, null, NOW));

        assertThat(dto.getBalance()).isEqualTo("15000");
        assertThat(dto.getOverdraftLimit()).isEqualTo("0");
    }

    @Test
    void an_account_without_a_limit_has_no_overdraft_limit() {
        assertThat(accountMapper.toAccountDto(new Account(ID, EUR, null, -12550, "Settlement EUR", NOW))
                .getOverdraftLimit()).isNull();
    }

    @Test
    void a_balance_names_its_account_currency_and_time() {
        Account account = new Account(ID, EUR, 0L, 12550, null, NOW);

        BalanceDto dto = accountMapper.toBalanceDto(new BalanceView(account, Money.of("100.00", EUR), NOW));

        assertThat(dto.getAccountId()).isEqualTo(ID);
        assertThat(dto.getCurrency()).isEqualTo("EUR");
        assertThat(dto.getBalance()).isEqualTo("100.00");
        assertThat(dto.getAsOf()).isEqualTo(NOW_UTC);
    }

    @Test
    void a_page_of_entries_carries_a_cursor_to_the_next_one() {
        UUID transactionId = UUID.randomUUID();
        AccountEntryView entry = new AccountEntryView(7, transactionId, "Payout", Direction.DEBIT,
                Money.of("100.00", EUR), Money.of("25.50", EUR), NOW);

        AccountEntryPageDto page = accountMapper.toAccountEntryPageDto(new EntryPage(List.of(entry), 7L));

        AccountEntryDto item = page.getItems().get(0);
        assertThat(item.getTransactionId()).isEqualTo(transactionId);
        assertThat(item.getDescription()).isEqualTo("Payout");
        assertThat(item.getDirection()).isEqualTo(DirectionDto.DEBIT);
        assertThat(item.getAmount()).isEqualTo("100.00");
        assertThat(item.getBalanceAfter()).isEqualTo("25.50");
        assertThat(item.getRecordedAt()).isEqualTo(NOW_UTC);
        assertThat(accountMapper.decodeCursor(page.getNextCursor())).isEqualTo(7L);
    }

    @Test
    void the_last_page_has_no_cursor() {
        assertThat(accountMapper.toAccountEntryPageDto(new EntryPage(List.of(), null)).getNextCursor()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 123456789, Long.MAX_VALUE})
    void a_cursor_decodes_to_the_entry_id_it_was_made_from(long entryId) {
        assertThat(accountMapper.decodeCursor(accountMapper.encodeCursor(entryId))).isEqualTo(entryId);
    }

    @Test
    void no_cursor_means_the_first_page() {
        assertThat(accountMapper.decodeCursor(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a cursor!", "AAAA"})
    void a_cursor_this_api_didnt_issue_is_a_bad_request(String cursor) {
        assertThatThrownBy(() -> accountMapper.decodeCursor(cursor))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.MALFORMED_REQUEST));
    }

    @Test
    void the_requested_overdraft_limit_is_null_when_not_given() {
        assertThat(accountMapper.toRequestedOverdraftLimit(new OpenAccountRequestDto("EUR"))).isNull();
        assertThat(accountMapper.toRequestedOverdraftLimit(new OpenAccountRequestDto("EUR").overdraftLimit("50.00")))
                .isEqualTo(new BigDecimal("50.00"));
    }
}
