package org.openfs.lanbilling.dreamkas.model;

public class ReceiptTotal {
	private final int priceSum;

	public ReceiptTotal(int priceSum) {
		this.priceSum = priceSum;
	}

	public int getPriceSum() {
		return priceSum;
	}
}
