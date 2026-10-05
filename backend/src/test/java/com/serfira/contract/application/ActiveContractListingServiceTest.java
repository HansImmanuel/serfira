package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStatus;
import com.serfira.contract.infrastructure.ContractRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the keyset bridge in {@link ActiveContractListingService} (CR-08). */
class ActiveContractListingServiceTest {

	private final ContractRepository contracts = mock(ContractRepository.class);
	private final ActiveContractListingService service = new ActiveContractListingService(contracts);

	@Test
	void passesActiveStatusAfterIdAndLimitThrough() {
		UUID afterId = new UUID(0L, 7L);
		List<UUID> page = List.of(UUID.randomUUID(), UUID.randomUUID());
		when(contracts.findActiveContractIdsAfter(eq(ContractStatus.ACTIVE), eq(afterId), eq(Limit.of(50))))
				.thenReturn(page);

		List<UUID> result = service.findActiveContractIdsAfter(afterId, 50);

		assertThat(result).isEqualTo(page);
		verify(contracts).findActiveContractIdsAfter(ContractStatus.ACTIVE, afterId, Limit.of(50));
	}

	@Test
	void rejectsANonPositiveLimit() {
		assertThatThrownBy(() -> service.findActiveContractIdsAfter(new UUID(0L, 0L), 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("limit");
	}

	@Test
	void rejectsANullAfterId() {
		assertThatThrownBy(() -> service.findActiveContractIdsAfter(null, 10))
				.isInstanceOf(NullPointerException.class);
	}
}
