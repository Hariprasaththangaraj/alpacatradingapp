package com.stockmarket.orderplacing.service;

import com.stockmarket.orderplacing.entity.OrderRequest;

import java.util.Map;

public interface OrderService {
    Map<String, Object> placeOrder(OrderRequest orderRequest);
}
