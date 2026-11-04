package com.firestar.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体。所有业务接口返回此结构，约定：
 *   code = 0 表示成功，非 0 表示业务失败（与 HTTP 状态码解耦）；
 *   message 为可读提示（对接前端 Toast / 错误提示）；
 *   data 为业务数据（无数据时可为 null）。
 * 字段命名对齐前端 api/request.ts 的解析约定（code / message / data）。
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private int code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public static <T> Result<T> error(String message) {
        return error(500, message);
    }
}
