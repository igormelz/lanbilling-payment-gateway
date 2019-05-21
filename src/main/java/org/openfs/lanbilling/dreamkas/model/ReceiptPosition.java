package org.openfs.lanbilling.dreamkas.model;

public class ReceiptPosition {
	private final String name;
	private String type;
	private int quantity;
	private int price;
	private int priceSum;
	private String tax;
	private int taxSum;
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

	public int getPrice() {
		return price;
	}

	public void setPrice(int price) {
		this.price = price;
	}

	public int getPriceSum() {
		return priceSum;
	}

	public void setPriceSum(int priceSum) {
		this.priceSum = priceSum;
	}

	public String getTax() {
		return tax;
	}

	public void setTax(String tax) {
		this.tax = tax;
	}

	public int getTaxSum() {
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
