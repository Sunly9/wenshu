package com.docmind.api.dto;

/** 统一错误响应体 */
public record ApiError(int code, String message) {}
