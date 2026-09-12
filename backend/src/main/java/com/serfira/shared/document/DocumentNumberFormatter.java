package com.serfira.shared.document;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;

/**
 * Pure formatting logic for business document numbers (TECH SPEC §1.1).
 *
 * <p>Counter keys are scoped by type and period (e.g. {@code CONTRACT_202609} or
 * {@code QUOTE_20260912}); the sequence part is zero-padded to four digits ({@code XXXX}) and
 * simply grows wider afterwards — uniqueness matters more than gapless width.
 */
public final class DocumentNumberFormatter {

	private static final EnumMap<DocumentType, DateTimeFormatter> PERIOD_FORMATTERS = periodFormatters();

	private DocumentNumberFormatter() {
	}

	/** Returns the counter row key for the given type and instant, e.g. {@code CONTRACT_202609}. */
	public static String counterKey(DocumentType type, OffsetDateTime at) {
		return type.keyPrefix() + "_" + periodOf(type, at);
	}

	/** Returns the business document number, e.g. {@code MF-202609-0001}. */
	public static String format(DocumentType type, OffsetDateTime at, int sequence) {
		return "%s-%s-%04d".formatted(type.businessPrefix(), periodOf(type, at), sequence);
	}

	private static String periodOf(DocumentType type, OffsetDateTime at) {
		return at.format(PERIOD_FORMATTERS.get(type));
	}

	private static EnumMap<DocumentType, DateTimeFormatter> periodFormatters() {
		EnumMap<DocumentType, DateTimeFormatter> map = new EnumMap<>(DocumentType.class);
		for (DocumentType type : DocumentType.values()) {
			map.put(type, DateTimeFormatter.ofPattern(type.periodPattern()));
		}
		return map;
	}
}