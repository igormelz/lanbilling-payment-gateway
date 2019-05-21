package org.openfs.lanbilling.dreamkas.model;

public class ReceiptPayment {
	private final int sum;
	private final String type;

	public ReceiptPayment(int amount, String type) {
		this.sum = amount * 100;
		this.type = type;
	}
	
	public int getSum() {
		return sum;
	}

	public String getType() {
		return type;
	}

}