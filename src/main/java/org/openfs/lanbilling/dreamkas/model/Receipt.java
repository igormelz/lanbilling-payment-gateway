package org.openfs.lanbilling.dreamkas.model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dreamkas Receipt Request
 */
public class Receipt {
	public final static String OPERATION_SALE = "SALE";
	public final static String OPERATION_REFUND = "REFUND";
	public final static String OPERATION_OUTFLOW = "OUTFLOW";
	public final static String OPERATION_OUTFLOW_REFUND = "OUTFLOW_REFUND";

	public final static String TAXMODE_DEFAULT = "DEFAULT";
	public final static String TAXMODE_SIMPLE = "SIMPLE";
	public final static String TAXMODE_SIMPLE_WO = "SIMPLE_WO";
	public final static String TAXMODE_ENVD = "ENVD";
	public final static String TAXMODE_AGRICULT = "AGRICULT";
	public final static String TAXMODE_PATENT = "PATENT";

	public final static String GOODS_COUNTABLE = "COUNTABLE";
	public final static String GOODS_SCALEABLE = "SCALEABLE";
	public final static String GOODS_SHOES = "SHOES";
	public final static String GOODS_CLOTHES = "CLOTHES";
	public final static String GOODS_SERVICE = "SERVICE"; // by response
	public final static String GOODS_TOBACCO = "TOBACCO";

	public final static String NDS_NO_TAX = "NDS_NO_TAX";
	public final static String NDS_0 = "NDS_0";
	public final static String NDS_10 = "NDS_10";
	public final static String NDS_20 = "NDS_20";
	public final static String NDS_10_CALCULATED = "NDS_10_CALCULATED";
	public final static String NDS_20_CALCULATED = "NDS_20_CALCULATED";

	public final static String PAYMENT_CASH = "CASH";
	public final static String PAYMENT_CASHLESS = "CASHLESS";
	public final static String PAYMENT_PREPAID = "PREPAID";
	public final static String PAYMENT_CREDIT = "CREDIT";
	public final static String PAYMENT_CONSIDERATION = "CONSIDERATION";

	private int deviceId;
	private String type;
	private int timeout;
	private String taxMode;
	private ReceiptPosition[] positions;
	private ReceiptPayment[] payments;
	private ReceiptFfdTag[] tags;
	private ReceiptAttributes attributes;
	private ReceiptTotal total;

	public int getDeviceId() {
		return deviceId;
	}

	public void setDeviceId(int deviceId) {
		this.deviceId = deviceId;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	public String getTaxMode() {
		return taxMode;
	}

	public void setTaxMode(String taxMode) {
		this.taxMode = taxMode;
	}

	public ReceiptPosition[] getPositions() {
		return positions;
	}

	public void setPositions(ReceiptPosition[] positions) {
		this.positions = positions;
	}

	public ReceiptPayment[] getPayments() {
		return payments;
	}

	public void setPayments(ReceiptPayment[] payments) {
		this.payments = payments;
	}

	public ReceiptFfdTag[] getTags() {
		return tags;
	}

	public void setTags(ReceiptFfdTag[] tags) {
		this.tags = tags;
	}

	public ReceiptAttributes getAttributes() {
		return attributes;
	}

	public void setAttributes(ReceiptAttributes attributes) {
		this.attributes = attributes;
	}

	public ReceiptTotal getTotal() {
		return total;
	}

	public void setTotal(ReceiptTotal total) {
		this.total = total;
	}

	public static Builder builder(int deviceId, String taxmode) {
		return new Builder(deviceId, taxmode);
	}
	
	public static final class Builder {
		private final int deviceId;
		private final String taxMode;
		private final List<ReceiptPosition> positions = new ArrayList<>();
		private final List<ReceiptPayment> payments = new ArrayList<>();
		private ReceiptAttributes attributes;

		Builder(final int deviceId, final String taxMode) {
			this.deviceId = deviceId;
			this.taxMode = taxMode;
		}

		public Builder addNoTaxServicePosition(String name, long price) {
			ReceiptPosition pos = new ReceiptPosition(name);
			pos.setType(GOODS_SERVICE);
			pos.setQuantity(1);
			pos.setPrice(price * 100);
			pos.setPriceSum(price * 100);
			pos.setTax(NDS_NO_TAX);
			this.positions.add(pos);
			return this;
		}

		public Builder addPayment(int amount, String paymentType) {
			this.payments.add(new ReceiptPayment(amount, paymentType));
			return this;
		}

		public Builder addPosition(ReceiptPosition position) {
			this.positions.add(position);
			return this;
		}

		public Builder addAttributes(ReceiptAttributes attributes) {
			this.attributes = attributes;
			return this;
		}

		public Builder addPhoneAttribute(final String phone) {
			if (this.attributes == null) {
				this.attributes = new ReceiptAttributes();
			}
			this.attributes.setPhone(phone);
			return this;
		}

		public Builder addEmailAttribute(final String email) {
			if (this.attributes == null) {
				this.attributes = new ReceiptAttributes();
			}
			this.attributes.setEmail(email);
			return this;
		}
		
		public Receipt buildSaleReceipt() {
			Receipt receipt = new Receipt();
			receipt.setDeviceId(deviceId);
			receipt.setType(OPERATION_SALE);
			receipt.setTaxMode(taxMode);
			receipt.setPositions(positions.toArray(new ReceiptPosition[positions.size()]));
			receipt.setPayments(payments.toArray(new ReceiptPayment[payments.size()]));
			if (this.attributes != null) {
				receipt.setAttributes(attributes);
			}
			long totalSum = payments.stream().collect(Collectors.averagingLong(p -> p.getSum())).longValue();
			receipt.setTotal(new ReceiptTotal(totalSum));
			return receipt;
		}
	}

}
