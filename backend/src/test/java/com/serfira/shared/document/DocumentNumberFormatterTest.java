package com.serfira.shared.document;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentNumberFormatterTest {

	private static OffsetDateTime at(int year, int month, int day) {
		return OffsetDateTime.of(year, month, day, 12, 0, 0, 0, ZoneOffset.ofHours(7));
	}

	@Test
	void counterKeyUsesTypePrefixAndMonthPeriod() {
		assertThat(DocumentNumberFormatter.counterKey(DocumentType.CONTRACT, at(2026, 9, 12)))
				.isEqualTo("CONTRACT_202609");
		assertThat(DocumentNumberFormatter.counterKey(DocumentType.PAYMENT, at(2026, 12, 1)))
				.isEqualTo("PAYMENT_202612");
	}

	@Test
	void quoteCounterKeyIsDaily() {
		assertThat(DocumentNumberFormatter.counterKey(DocumentType.QUOTE, at(2026, 1, 31)))
				.isEqualTo("QUOTE_20260131");
	}

	@Test
	void formatProducesBusinessPrefixPeriodAndZeroPaddedSequence() {
		assertThat(DocumentNumberFormatter.format(DocumentType.CONTRACT, at(2026, 9, 12), 1))
				.isEqualTo("MF-202609-0001");
		assertThat(DocumentNumberFormatter.format(DocumentType.PAYMENT, at(2026, 3, 8), 42))
				.isEqualTo("PAY-202603-0042");
		assertThat(DocumentNumberFormatter.format(DocumentType.QUOTE, at(2026, 1, 31), 7))
				.isEqualTo("Q-20260131-0007");
		assertThat(DocumentNumberFormatter.format(DocumentType.SETTLEMENT, at(2026, 10, 30), 999))
				.isEqualTo("SET-202610-0999");
	}

	@Test
	void sequenceWiderThanFourDigitsIsNotTruncated() {
		assertThat(DocumentNumberFormatter.format(DocumentType.CONTRACT, at(2026, 9, 12), 10000))
				.isEqualTo("MF-202609-10000");
	}

	@Test
	void formatAndCounterKeyAreConsistentForTheSameInstant() {
		OffsetDateTime at = at(2026, 2, 28);
		String period = DocumentNumberFormatter.counterKey(DocumentType.CONTRACT, at)
				.substring("CONTRACT_".length());
		assertThat(DocumentNumberFormatter.format(DocumentType.CONTRACT, at, 1)).startsWith("MF-" + period);
	}

	@Test
	void contractNoMatchesDocumentedFormatFromDomainModel() {
		String no = DocumentNumberFormatter.format(DocumentType.CONTRACT, at(2026, 9, 12), 1);
		assertThat(no).matches("MF-\\d{6}-\\d{4}");
		assertThat(LocalDate.of(2026, 9, 12).toString()).isEqualTo("2026-09-12");
	}
}