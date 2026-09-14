package com.serfira.contract.domain;

import com.serfira.shared.audit.Auditable;
import com.serfira.shared.security.AesGcmStringAttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * <p>ADR-004 at-rest PII protection: {@code nik} and {@code phone} are stored AES-256-GCM ciphertext
 * (application-level {@link AesGcmStringAttributeConverter}) — this entity holds plaintext in memory.
 * Uniqueness and search NEVER use the ciphertext; {@code nikHash} (HMAC-SHA-256, unique) and
 * {@code phoneLookup} (HMAC-SHA-256, unique — one phone per customer) are the lookup keys, computed by
 * the caller via {@code PiiHasher} before construction.
 */
@Entity
@Table(name = "customer", uniqueConstraints = {
		@UniqueConstraint(name = "uk_customer_nik_hash", columnNames = "nik_hash"),
		@UniqueConstraint(name = "uk_customer_phone_lookup", columnNames = "phone_lookup")
})
public class Customer extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "full_name", nullable = false, length = 120)
	private String fullName;

	@Convert(converter = AesGcmStringAttributeConverter.class)
	@Column(name = "nik", nullable = false, columnDefinition = "text")
	private String nik;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "nik_hash", nullable = false, columnDefinition = "char(64)")
	private String nikHash;

	@Convert(converter = AesGcmStringAttributeConverter.class)
	@Column(name = "phone", nullable = false, columnDefinition = "text")
	private String phone;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "phone_lookup", nullable = false, columnDefinition = "char(64)")
	private String phoneLookup;

	@Column(name = "address", columnDefinition = "text")
	private String address;

	protected Customer() {
		// JPA
	}

	/**
	 * @param nikHash      HMAC-SHA-256 of the normalized NIK ({@code PiiHasher.hashNik})
	 * @param phoneLookup  HMAC-SHA-256 of the normalized phone ({@code PiiHasher.hashPhone})
	 */
	public Customer(String fullName, String nik, String nikHash, String phone, String phoneLookup, String address) {
		this.fullName = fullName;
		this.nik = nik;
		this.nikHash = nikHash;
		this.phone = phone;
		this.phoneLookup = phoneLookup;
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

	public String getPhoneLookup() {
		return phoneLookup;
	}

	public String getAddress() {
		return address;
	}
}