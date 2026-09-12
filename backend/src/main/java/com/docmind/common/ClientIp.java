package com.docmind.common;

import jakarta.servlet.http.HttpServletRequest;

/** 取客户端 IP：生产在 nginx 之后，优先读 X-Forwarded-For 的第一个地址 */
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
