package com.serfira.contract.application.event;

import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.shared.outbox.OutboxPayload;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** {@code ContractActivated} (ADR-027): a DRAFT contract went live and its schedule was generated. No PII. */
public record ContractActivatedPayload(int schemaVersion, UUID contractId, String contractNo, LocalDate startDate,
		BigDecimal principal, int tenorMonths, InterestScheme interestScheme) implements OutboxPayload {

	public static ContractActivatedPayload of(Contract contract) {
		return new ContractActivatedPayload(SCHEMA_VERSION, contract.getId(), contract.getContractNo(),
				contract.getStartDate(), contract.getPrincipal(), contract.getTenorMonths(),
				contract.getInterestScheme());
	}
}
