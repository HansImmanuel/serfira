package com.serfira.shared.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link SystemParameter}. The only supported query is "the row in force on a
 * business date": latest {@code effective_date} that is not in the future, so a future-dated row is
 * ignored until its date arrives.
 */
public interface SystemParameterRepository extends JpaRepository<SystemParameter, UUID> {

	Optional<SystemParameter> findFirstByParamKeyAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(
			String paramKey, LocalDate businessDate);
}