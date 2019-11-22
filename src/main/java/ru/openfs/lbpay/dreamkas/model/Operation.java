package ru.openfs.lbpay.dreamkas.model;

public class Operation {
	public static final String PENDING = "PENDING";	
	public static final String IN_PROGRESS = "IN_PROGRESS";	
	public static final String SUCCESS = "SUCCESS";
	public static final String ERROR = "ERROR";
	
	private String id;
	private String externalId;
	private String createdAt;
	private String type;
	private String completedAt;
	private OperationData data;
	private String status;
	
	public Operation() {}
	
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(String createdAt) {
		this.createdAt = createdAt;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(String completedAt) {
		this.completedAt = completedAt;
	}

	public OperationData getData() {
		return data;
	}

	public void setData(OperationData data) {
		this.data = data;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

}
