package ru.openfs.lbpay.lbsoap.model;

public class LbSoapPaymentInfo {
    private final String customerName;
    private final String customerPhone;
    private final String customerEmail;
    private Double amount;

    public LbSoapPaymentInfo(String name, String email, String phone) {
        this.customerName = name;
        this.customerEmail = email;
        this.customerPhone = phone;
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

    public void setAmount(Double amount) {
        this.amount = amount;
    }
}