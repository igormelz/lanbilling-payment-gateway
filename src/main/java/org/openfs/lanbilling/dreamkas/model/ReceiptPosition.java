package org.openfs.lanbilling.dreamkas.model;

public class ReceiptPosition {
	private final String name;
	private String type;
	private int quantity;
	private long price;
	private long priceSum;
	private String tax;
	private long taxSum;
	private ReceiptFfdTag[] tags;

	public ReceiptPosition(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public int getQuantity() {
		return quantity;
	}

	public void setQuantity(int quantity) {
		this.quantity = quantity;
	}

	public long getPrice() {
		return price;
	}

	public void setPrice(long price) {
		this.price = price;
	}

	public long getPriceSum() {
		return priceSum;
	}

	public void setPriceSum(long priceSum) {
		this.priceSum = priceSum;
	}

	public String getTax() {
		return tax;
	}

	public void setTax(String tax) {
		this.tax = tax;
	}

	public long getTaxSum() {
		return taxSum;
	}

	public void setTaxSum(int taxSum) {
		this.taxSum = taxSum;
	}

	public ReceiptFfdTag[] getTags() {
		return tags;
	}

	public void setTags(ReceiptFfdTag[] tags) {
		this.tags = tags;
	}
}
