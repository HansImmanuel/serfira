package com.serfira.settlement.application;

import com.serfira.settlement.infrastructure.SettlementRepository;
import com.serfira.shared.clock.FixedClock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T36 — settlement cash covers the business dates {@code [from, to]} in the application zone (Asia/Jakarta,
 * UTC+7): the half-open instant range runs from {@code from} 00:00 Jakarta to {@code to + 1} 00:00 Jakarta, so a
 * settlement executed at 23:59 Jakarta on the last day counts and one executed at 00:00 the next day does not.
 */
class SettlementCashServiceTest {

	private static final ZoneOffset JAKARTA = ZoneOffset.ofHours(7);

	private final SettlementRepository settlements = mock(SettlementRepository.class);
	private final SettlementCashService service = new SettlementCashService(settlements,
			new FixedClock(LocalDate.of(2026, 3, 31)));

	@Test
	void theBusinessDateRangeBecomesAHalfOpenJakartaInstantRange() {
		OffsetDateTime start = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, JAKARTA);
		OffsetDateTime end = OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, JAKARTA);
		when(settlements.sumCashReceivedExecutedBetween(any(), any())).thenReturn(new BigDecimal("150000"));

		BigDecimal cash = service.sumCashReceived(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

		assertThat(cash).isEqualTo(new BigDecimal("150000.00"));
		verify(settlements).sumCashReceivedExecutedBetween(eq(start), eq(end));
	}

	@Test
	void aReversedRangeIsRejected() {
		assertThatThrownBy(() -> service.sumCashReceived(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
