package com.serfira.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.serfira.auth.domain.AppUser;

import jakarta.persistence.LockModeType;

/**
 * JPA repository for {@link AppUser} (T21a, extended by T21b).
 *
 * <p>{@link #findLockedById(UUID)} takes a PostgreSQL row lock ({@code SELECT ... FOR UPDATE}) and
 * is the serialization point for lockout bookkeeping. Failed-login recording runs in
 * {@code LoginAttemptRecorder} with a {@code REQUIRES_NEW} transaction because the login response
 * is a 401 and the failure must survive it; successful-login state changes are committed by
 * {@code SuccessfulLoginRecorder}'s transaction. Both re-read the row through this method, so
 * concurrent attempts on the same account cannot lose a counter increment or clear a lock that
 * another request has just committed.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

	Optional<AppUser> findByUsername(String username);

	/**
	 * Loads the account while holding a pessimistic write lock for the rest of the caller's
	 * transaction. The explicit query is required because Spring Data would otherwise read
	 * {@code findLockedById} as a property named {@code lockedById}.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from AppUser u where u.id = :id")
	Optional<AppUser> findLockedById(@Param("id") UUID id);
}
