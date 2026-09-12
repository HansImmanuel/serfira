package com.serfira.shared.document;

/**
 * Business-facing document types and the components of their number formats
 * (TECH SPEC §1.1, Addendum §1.4):
 *
 * <ul>
 *   <li>{@code MF-YYYYMM-XXXX} — contract</li>
 *   <li>{@code PAY-YYYYMM-XXXX} — payment</li>
 *   <li>{@code Q-YYYYMMDD-XXXX} — settlement quote (daily period)</li>
 *   <li>{@code SET-YYYYMM-XXXX} — settlement</li>
 * </ul>
 *
 * @param keyPrefix       prefix used inside the {@code document_number_counter.counter_key}
 * @param businessPrefix  prefix printed on the business document number
 * @param periodPattern   {@link java.time.format.DateTimeFormatter} pattern identifying the
 *                        counter period (monthly {@code yyyyMM} or daily {@code yyyyMMdd})
 */
public enum DocumentType {

	CONTRACT("CONTRACT", "MF", "yyyyMM"),
	PAYMENT("PAYMENT", "PAY", "yyyyMM"),
	QUOTE("QUOTE", "Q", "yyyyMMdd"),
	SETTLEMENT("SETTLEMENT", "SET", "yyyyMM");

	private final String keyPrefix;
	private final String businessPrefix;
	private final String periodPattern;

	DocumentType(String keyPrefix, String businessPrefix, String periodPattern) {
		this.keyPrefix = keyPrefix;
		this.businessPrefix = businessPrefix;
		this.periodPattern = periodPattern;
	}

	public String keyPrefix() {
		return keyPrefix;
	}

	public String businessPrefix() {
		return businessPrefix;
	}

	public String periodPattern() {
		return periodPattern;
	}
}