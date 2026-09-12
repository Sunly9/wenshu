package com.docmind.api.controller;

import com.docmind.api.dto.ApiError;
import com.docmind.common.exception.ApiException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getStatus(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<ApiError> handleValidation(Exception e) {
        String message = e instanceof MethodArgumentNotValidException manv
                ? manv.getBindingResult().getFieldErrors().get(0).getDefaultMessage()
                : e.getMessage();
        return ResponseEntity.badRequest().body(new ApiError(400, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ApiError(400, "请求体格式错误（需要 UTF-8 的 JSON）"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(new ApiError(413, "单个文档不能超过 50MB"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(new ApiError(404, "接口不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.internalServerError().body(new ApiError(500, "服务器开小差了，请稍后再试"));
    }
}
