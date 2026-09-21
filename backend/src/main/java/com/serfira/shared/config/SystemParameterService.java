package com.serfira.shared.config;

import com.serfira.shared.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Reads typed global configuration from {@code system_parameter} (Addendum §1.2–1.3).
 *
 * <p>The row in force is the latest row whose {@code effective_date} is not in the future, relative
 * to the injectable {@link Clock} business date — never "today" from the JVM.
 *
 * <p>A missing or unparsable key is a <b>deployment/configuration defect</b>, not client input:
 * it fails loudly as {@link IllegalStateException} (HTTP 500 through the standard envelope) and is
 * logged at ERROR with the key name only. Values are never logged.
 */
@Service
@Transactional(readOnly = true)
public class SystemParameterService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SystemParameterService.class);

	private final SystemParameterRepository parameters;
	private final Clock clock;

	public SystemParameterService(SystemParameterRepository parameters, Clock clock) {
		this.parameters = parameters;
		this.clock = clock;
	}

	/** Raw configured value in force on the current business date. */
	public String requireString(String paramKey) {
		return requireString(paramKey, clock.today());
	}

	/** Raw configured value in force on {@code businessDate}. */
	public String requireString(String paramKey, LocalDate businessDate) {
		return requireRow(paramKey, businessDate).getParamValue().trim();
	}

	/** Configured value in force today, parsed as {@code int}. */
	public int requireInt(String paramKey) {
		return requireInt(paramKey, clock.today());
	}

	/** Configured value in force on {@code businessDate}, parsed as {@code int}. */
	public int requireInt(String paramKey, LocalDate businessDate) {
		String raw = requireString(paramKey, businessDate);
		try {
			return Integer.parseInt(raw);
		} catch (NumberFormatException ex) {
			throw unparsable(paramKey, "int", ex);
		}
	}

	/** Configured value in force today, parsed as {@link BigDecimal} (rates keep their DB scale). */
	public BigDecimal requireDecimal(String paramKey) {
		return requireDecimal(paramKey, clock.today());
	}

	/** Configured value in force on {@code businessDate}, parsed as {@link BigDecimal}. */
	public BigDecimal requireDecimal(String paramKey, LocalDate businessDate) {
		String raw = requireString(paramKey, businessDate);
		try {
			return new BigDecimal(raw);
		} catch (NumberFormatException ex) {
			throw unparsable(paramKey, "decimal", ex);
		}
	}

	private SystemParameter requireRow(String paramKey, LocalDate businessDate) {
		return parameters
				.findFirstByParamKeyAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(paramKey, businessDate)
				.orElseThrow(() -> {
					LOGGER.error("system parameter '{}' has no row effective on {}", paramKey, businessDate);
					return new IllegalStateException(
							"system parameter '" + paramKey + "' is not configured for " + businessDate);
				});
	}

	private static IllegalStateException unparsable(String paramKey, String targetType, RuntimeException cause) {
		LOGGER.error("system parameter '{}' is not a valid {}", paramKey, targetType);
		return new IllegalStateException("system parameter '" + paramKey + "' is not a valid " + targetType, cause);
	}
}