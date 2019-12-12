package ru.openfs.lbpay.lbsoap.model;

public class LbPaymentInfo {
    private final String customerName;
    private final String customerPhone;
    private final String customerEmail;
    private final Double amount;

    public LbPaymentInfo(String name, String email, String phone, Double amount) {
        this.customerName = name;
        this.customerEmail = email;
        this.customerPhone = phone;
        this.amount = amount;
    }

    public String getCustomerName() {
        return this.customerName;
    }

    public String getCustomerEmail() {
        return this.customerEmail;
    }

    public String getCustomerPhone() {
        return this.customerPhone;
    }

    public Double getAmount() {
        return this.amount;
    }
}