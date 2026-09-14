package com.serfira.shared.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Seals/unseals a plaintext String into an at-rest AES-256-GCM envelope (ADR-004).
 *
 * <p>Applied explicitly with {@code @Convert(converter = ...)} on {@code Customer.nik} and
 * {@code Customer.phone}. The entity consequently holds plaintext in memory (JPA lifecycle) while the
 * database only ever stores the {@code v1:...} ciphertext.
 */
@Converter
public class AesGcmStringAttributeConverter implements AttributeConverter<String, String> {

	@Override
	public String convertToDatabaseColumn(String plaintext) {
		return plaintext == null ? null : PiiSecuritySupport.encrypt(plaintext);
	}

	@Override
	public String convertToEntityAttribute(String ciphertext) {
		return ciphertext == null ? null : PiiSecuritySupport.decrypt(ciphertext);
	}
}