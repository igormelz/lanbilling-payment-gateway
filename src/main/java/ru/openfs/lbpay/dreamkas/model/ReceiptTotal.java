package ru.openfs.lbpay.dreamkas.model;

public class ReceiptTotal {
	private final long priceSum;

	public ReceiptTotal(long priceSum) {
		this.priceSum = priceSum;
	}

	public long getPriceSum() {
		return priceSum;
	}
}
