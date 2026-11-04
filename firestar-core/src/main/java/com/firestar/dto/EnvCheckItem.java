package com.firestar.dto;

/**
 * 环境预检项（安装向导 /install 页展示）。
 * 对应 {@code InitResetServiceImpl.doEnvCheck()} 的检查结果。
 */
public class EnvCheckItem {

    private String name;
    private boolean ok;
    private String detail;

    public EnvCheckItem() {}

    public EnvCheckItem(String name, boolean ok, String detail) {
        this.name = name;
        this.ok = ok;
        this.detail = detail;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
