package com.serfira.contract.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Raw-column access to the stored customer PII for the T28b re-keying pass (ADR-025 D9). The {@link
 * com.serfira.contract.domain.Customer} entity holds plaintext only, so a re-key bypasses JPA: these statements read
 * and write the envelopes and lookup hashes exactly as stored. They only ever carry values the application encrypted
 * and hashed (ADR-004); no key and no plaintext reaches SQL.
 */
public class CustomerPiiRows {

	private static final String COLUMNS = "SELECT id, nik, nik_hash, phone, phone_lookup FROM customer";

	private final JdbcClient jdbc;

	public CustomerPiiRows(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Up to {@code limit} rows with an id after {@code after}, in id order; page from {@code new UUID(0L, 0L)}. */
	public List<CustomerPiiRow> pageAfter(UUID after, int limit) {
		return jdbc.sql(COLUMNS + " WHERE id > :after ORDER BY id LIMIT :limit")
				.param("after", after)
				.param("limit", limit)
				.query(CustomerPiiRows::map)
				.list();
	}

	public Optional<CustomerPiiRow> findById(UUID id) {
		return jdbc.sql(COLUMNS + " WHERE id = :id")
				.param("id", id)
				.query(CustomerPiiRows::map)
				.optional();
	}

	/** Replaces one row's envelopes and lookup hashes and stamps the audit columns; returns the rows updated. */
	public int rewrite(UUID id, String nikEnvelope, String nikHash, String phoneEnvelope, String phoneLookup,
			OffsetDateTime updatedAt, UUID updatedBy) {
		return jdbc.sql("""
				UPDATE customer SET nik = :nik, nik_hash = :nikHash, phone = :phone, phone_lookup = :phoneLookup,
				       updated_at = :updatedAt, updated_by = :updatedBy WHERE id = :id""")
				.param("nik", nikEnvelope)
				.param("nikHash", nikHash)
				.param("phone", phoneEnvelope)
				.param("phoneLookup", phoneLookup)
				.param("updatedAt", updatedAt)
				.param("updatedBy", updatedBy)
				.param("id", id)
				.update();
	}

	private static CustomerPiiRow map(ResultSet rs, int rowNum) throws SQLException {
		return new CustomerPiiRow(rs.getObject("id", UUID.class), rs.getString("nik"), rs.getString("nik_hash"),
				rs.getString("phone"), rs.getString("phone_lookup"));
	}
}
