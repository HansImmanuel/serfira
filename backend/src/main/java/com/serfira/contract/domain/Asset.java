package com.serfira.contract.domain;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * The financed asset (goods object) of a contract (03_DOMAIN_MODEL.md §1.2).
 *
 * <p>{@code serial_no} and {@code plate_no} are each unique only when present (partial unique indexes).
 */
@Entity
@Table(name = "asset")
public class Asset extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "asset_type", nullable = false, length = 20)
	private AssetType assetType;

	@Column(name = "brand", nullable = false, length = 80)
	private String brand;

	@Column(name = "model", nullable = false, length = 80)
	private String model;

	@Column(name = "serial_no", length = 40)
	private String serialNo;

	@Column(name = "plate_no", length = 20)
	private String plateNo;

	protected Asset() {
		// JPA
	}

	public Asset(AssetType assetType, String brand, String model, String serialNo, String plateNo) {
		this.assetType = assetType;
		this.brand = brand;
		this.model = model;
		this.serialNo = serialNo;
		this.plateNo = plateNo;
	}

	public UUID getId() {
		return id;
	}

	public AssetType getAssetType() {
		return assetType;
	}

	public String getBrand() {
		return brand;
	}

	public String getModel() {
		return model;
	}

	public String getSerialNo() {
		return serialNo;
	}

	public String getPlateNo() {
		return plateNo;
	}
}