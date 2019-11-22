package ru.openfs.lbpay.lbsoap.model;

import java.io.Serializable;
import java.util.Map;

public class LbServiceResponse implements Serializable {
    private static final long serialVersionUID = 2472612378764146433L;
    private final LbServiceResponseStatus status;
    private final Object body;

    public LbServiceResponse(LbServiceResponseStatus status, Object body) {
        this.status = status;
        this.body = body;
    }

    public LbServiceResponseStatus getStatus() {
        return this.status;
    }

    public boolean isSuccess() {
        return this.status == LbServiceResponseStatus.SUCCESS;
    }

    public boolean isFault() {
        return this.status == LbServiceResponseStatus.FAULT;
    }

    public Object getBody() {
        return this.body;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getValues() {
        if (this.body instanceof Map) {
            return (Map<String, Object>) this.body;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Object getValue(String name) {
        if (!(this.body instanceof Map)) {
            return null;
        }
        Map<String, Object> values = (Map<String, Object>) this.body;
        if (!values.containsKey(name)) {
            return null;
        }
        return values.get(name);
    }

    public Long getLong(String name) {
        return getValue(name) != null ? (long) getValue(name) : null;
    }

    public String getString(String name) {
        return getValue(name) != null ? getValue(name).toString() : null;
    }

}