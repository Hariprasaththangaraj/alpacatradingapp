package com.stockmarket.orderplacing.controller;

import com.stockmarket.orderplacing.entity.OrderRequest;
import com.stockmarket.orderplacing.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/")
public class OrderController {

    @Autowired
    OrderService orderService;

    @PostMapping("/order")
    public String placeOrder(@RequestBody OrderRequest orderRequest) {
        return "Order details : " + orderService.placeOrder(orderRequest);
    }
}
