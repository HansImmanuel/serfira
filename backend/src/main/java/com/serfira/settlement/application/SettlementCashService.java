package com.serfira.settlement.application;

import com.serfira.settlement.infrastructure.SettlementRepository;
import com.serfira.shared.clock.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Implementation of {@link SettlementCashPort} (T36): turns the business-date range into the half-open instant
 * range {@code [from 00:00, (to + 1) 00:00)} in the clock's zone, so a settlement executed late on the last day
 * of the period still counts and one executed just after midnight Jakarta time on the next day does not.
 */
@Service
public class SettlementCashService implements SettlementCashPort {

	private static final int MONEY_SCALE = 2;

	private final SettlementRepository settlements;
	private final Clock clock;

	public SettlementCashService(SettlementRepository settlements, Clock clock) {
		this.settlements = settlements;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public BigDecimal sumCashReceived(LocalDate from, LocalDate to) {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		if (to.isBefore(from)) {
			throw new IllegalArgumentException("to (" + to + ") must not be before from (" + from + ")");
		}
		ZoneId zone = clock.zone();
		OffsetDateTime start = from.atStartOfDay(zone).toOffsetDateTime();
		OffsetDateTime end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
		return settlements.sumCashReceivedExecutedBetween(start, end).setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}
}
