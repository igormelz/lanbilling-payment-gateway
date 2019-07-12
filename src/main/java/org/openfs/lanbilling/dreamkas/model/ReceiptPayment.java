package org.openfs.lanbilling.dreamkas.model;

public class ReceiptPayment {
	private final long sum;
	private final String type;

	public ReceiptPayment(long amount, String type) {
		this.sum = amount;
		this.type = type;
	}
	
	public long getSum() {
		return sum;
	}

	public String getType() {
		return type;
	}

}