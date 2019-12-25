package ru.openfs.lbpay;

public class PaymentGatewayConstants {
	public static final String CUSTOMER_NAME = "name";
	public static final String CUSTOMER_PHONE = "phone";
	public static final String CUSTOMER_EMAIL = "email";
	public static final String ORDER_AMOUNT = "amount";
	public static final String RECEIPT_TYPE = "receiptType";
	public static final String ORDER_NUMBER = "orderNumber";
	public static final String SBER_ORDER_NUMBER = "mdOrder";
	public static final String FORM_AGREEMENT = "uid";
	public static final String FORM_AMOUNT = "amount";

	// lb-pay http response codes
	// success 
	public static final int OK = 200;
	public static final int ACCEPTED = 202;
	public static final int NO_CONTENT = 204;
	// moved
	public static final int MOVED_PERMANENTLY = 301;
	// error
	public static final int BAD_REQUEST = 400;
	public static final int NOT_FOUND = 404;
	public static final int NOT_ACCEPTABLE = 406;
	public static final int INTERNAL_SERVER_ERROR = 500;

}