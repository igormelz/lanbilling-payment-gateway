package ru.openfs.lbpay.dreamkas.model;

public class OperationError {
	private String code;
	private String message;
	
	public OperationError() {}
	
	public String getCode() {
		return code;
	}
	public void setCode(String code) {
		this.code = code;
	}
	public String getMessage() {
		return message;
	}
	public void setMessage(String message) {
		this.message = message;
	}
}