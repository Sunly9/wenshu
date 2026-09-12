package com.docmind.common.exception;

/** 业务异常：message 直接面向用户展示，status 为 HTTP 状态码 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(String message) { this(400, message); }

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }
}
