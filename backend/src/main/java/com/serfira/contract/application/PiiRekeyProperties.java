package com.serfira.contract.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The re-keying runner's settings (ADR-025 D9): {@code serfira.maintenance.pii-rekey.mode} ({@code
 * SERFIRA_MAINTENANCE_PIIREKEY_MODE}, an unknown value fails startup) and {@code .batch-size} ({@code
 * SERFIRA_MAINTENANCE_PIIREKEY_BATCHSIZE}, the page size, at least 1). Whether the runner exists at all is {@code
 * serfira.maintenance.pii-rekey.enabled}, read by {@link PiiRekeyConfiguration}.
 */
@ConfigurationProperties("serfira.maintenance.pii-rekey")
public record PiiRekeyProperties(@DefaultValue("DRY_RUN") PiiRekeyMode mode, @DefaultValue("500") int batchSize) {

	public PiiRekeyProperties {
		if (batchSize < 1) {
			throw new IllegalArgumentException("serfira.maintenance.pii-rekey.batch-size must be at least 1; set the "
					+ "SERFIRA_MAINTENANCE_PIIREKEY_BATCHSIZE environment variable");
		}
	}
}
