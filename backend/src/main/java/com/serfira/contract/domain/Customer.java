package com.serfira.contract.domain;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Customer (borrower) of one or more financing contracts (03_DOMAIN_MODEL.md §1.1).
 *
 * <p>{@code nik} is encrypted at rest by an application converter (Sprint 2+) and is never used for
 * uniqueness — {@code nikHash} (HMAC-SHA-256 of the normalized NIK) is the unique key.
 */
@Entity
@Table(name = "customer", uniqueConstraints = {
		@UniqueConstraint(name = "uk_customer_nik_hash", columnNames = "nik_hash")
})
public class Customer extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "full_name", nullable = false, length = 120)
	private String fullName;

	@Column(name = "nik", nullable = false, length = 16)
	private String nik;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "nik_hash", nullable = false, columnDefinition = "char(64)")
	private String nikHash;

	@Column(name = "phone", length = 20)
	private String phone;

	@Column(name = "address", columnDefinition = "text")
	private String address;

	protected Customer() {
		// JPA
	}

	public Customer(String fullName, String nik, String nikHash, String phone, String address) {
		this.fullName = fullName;
		this.nik = nik;
		this.nikHash = nikHash;
		this.phone = phone;
		this.address = address;
	}

	public UUID getId() {
		return id;
	}

	public String getFullName() {
		return fullName;
	}

	public String getNik() {
		return nik;
	}

	public String getNikHash() {
		return nikHash;
	}

	public String getPhone() {
		return phone;
	}

	public String getAddress() {
		return address;
	}
}