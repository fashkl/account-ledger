package com.mohamedali.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mohamedali.ledger.ledger.adapter.out.persistence.JdbcLedgerPostingAdapter;
import com.mohamedali.ledger.ledger.adapter.out.persistence.order.JdbcOrderStateAdapter;
import com.mohamedali.ledger.ledger.application.port.out.order.OrderStatePersistencePort.BuyingPowerComponents;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.service.AccountTypePolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ReadReplicaRoutingTest {

    @Test
    void ledgerBalanceRead_usesReadReplicaTemplate() {
        JdbcTemplate primary = org.mockito.Mockito.mock(JdbcTemplate.class);
        JdbcTemplate replica = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountTypePolicy policy = new AccountTypePolicy();
        UUID accountId = UUID.randomUUID();
        when(replica.queryForObject("SELECT balance FROM account_balances WHERE account_id = ?", BigDecimal.class, accountId))
                .thenReturn(new BigDecimal("12.34"));

        JdbcLedgerPostingAdapter adapter = new JdbcLedgerPostingAdapter(primary, replica, policy);
        BigDecimal value = adapter.getBalance(accountId).orElse(BigDecimal.ZERO);

        assertThat(value).isEqualByComparingTo("12.34");
        verify(replica).queryForObject("SELECT balance FROM account_balances WHERE account_id = ?", BigDecimal.class, accountId);
        verify(primary, never()).queryForObject("SELECT balance FROM account_balances WHERE account_id = ?", BigDecimal.class, accountId);
    }

    @SuppressWarnings("unchecked")
    @Test
    void buyingPowerRead_usesReadReplicaTemplate() {
        JdbcTemplate primary = org.mockito.Mockito.mock(JdbcTemplate.class);
        JdbcTemplate replica = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID customerId = UUID.randomUUID();
        when(replica.query(
                ArgumentMatchers.anyString(),
                (RowMapper<BuyingPowerComponents>) ArgumentMatchers.any(RowMapper.class),
                ArgumentMatchers.eq(customerId),
                ArgumentMatchers.eq("AED")
        )).thenReturn(List.of(new BuyingPowerComponents(
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        )));

        JdbcOrderStateAdapter adapter = new JdbcOrderStateAdapter(primary, replica);
        var bp = adapter.getBuyingPowerComponentsForShare(customerId, Currency.AED);

        assertThat(bp.settledCash()).isEqualByComparingTo("100");
        verify(replica).query(
                ArgumentMatchers.anyString(),
                (RowMapper<BuyingPowerComponents>) ArgumentMatchers.any(RowMapper.class),
                ArgumentMatchers.eq(customerId),
                ArgumentMatchers.eq("AED")
        );
    }
}
