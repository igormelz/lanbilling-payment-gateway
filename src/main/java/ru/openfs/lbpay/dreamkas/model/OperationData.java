package ru.openfs.lbpay.dreamkas.model;

public class OperationData {
	private OperationError error;
	private String receiptId;

	public OperationData() {}
	
	public OperationError getError() {
		return error;
	}

	public void setError(OperationError error) {
		this.error = error;
	}

	public String getReceiptId() {
		return receiptId;
	}

	public void setReceiptId(String receiptId) {
		this.receiptId = receiptId;
	}
}
