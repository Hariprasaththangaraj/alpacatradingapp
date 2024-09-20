package com.stockmarket.orderplacing.entity;

public class OrderRequest {
    private String symbol_id;
    private String action;
    private Integer quantity;
    private float sl_percentage;
    private float target_percentage;

    public String getSymbol_id() {
        return symbol_id;
    }

    public void setSymbol_id(String symbol_id) {
        this.symbol_id = symbol_id;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public float getSl_percentage() {
        return sl_percentage;
    }

    public void setSl_percentage(float sl_percentage) {
        this.sl_percentage = sl_percentage;
    }

    public float getTarget_percentage() {
        return target_percentage;
    }

    public void setTarget_percentage(float target_percentage) {
        this.target_percentage = target_percentage;
    }

    public OrderRequest() {
    }

    public OrderRequest(String symbol_id, String action, Integer quantity, float sl_percentage, float target_percentage) {
        this.symbol_id = symbol_id;
        this.action = action;
        this.quantity = quantity;
        this.sl_percentage = sl_percentage;
        this.target_percentage = target_percentage;
    }
}
