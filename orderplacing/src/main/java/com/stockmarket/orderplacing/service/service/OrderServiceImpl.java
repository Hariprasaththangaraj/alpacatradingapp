package com.stockmarket.orderplacing.service.service;

import com.stockmarket.orderplacing.entity.OrderRequest;
import com.stockmarket.orderplacing.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Value("${alpaca.api.key}")
    private String key;

    @Value("${alpaca.api.secret}")
    private String secret;

    @Value("${alpaca.base.url}")
    private String url;

    private String executionPrice;

    @Autowired
    private final RestTemplate restTemplate;

    public OrderServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public Map<String, Object> placeOrder(OrderRequest orderRequest) {
        String ordersUrl = url + "/orders";

        // Setting API secret Key
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("APCA-API-KEY-ID", key);
        headers.set("APCA-API-SECRET-KEY", secret);

        // Preparing for the initial market order
        Map<String, Object> order = new HashMap<>();
        order.put("symbol", orderRequest.getSymbol_id().toUpperCase());
        order.put("qty", orderRequest.getQuantity());
        order.put("side", orderRequest.getAction().toLowerCase());
        order.put("type", "market");
        order.put("time_in_force", "gtc");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(order, headers);

        // Placing the market order
        ResponseEntity<Map> response = restTemplate.postForEntity(ordersUrl, entity, Map.class);

        if (response.getStatusCode() == HttpStatus.OK) {
            Map<String, Object> body = response.getBody();
            // Get the filled price
            executionPrice = body != null ? body.get("filled_avg_price").toString() : null;

            // Set up Stop Loss and Target orders after market order execution
            setStopLossOrder(orderRequest, headers, executionPrice);
            setTargetOrder(orderRequest, headers, executionPrice);

            return body;
        } else {
            throw new RuntimeException("Failed to place order");
        }
    }

    // Set up Stop Loss order based on the filled price
    private void setStopLossOrder(OrderRequest orderRequest, HttpHeaders headers, String executionPrice) {
        String ordersUrl = url + "/orders";

        // Calculating Stop Loss price
        double slPrice = Double.parseDouble(executionPrice) * (1 - (orderRequest.getSl_percentage() / 100));
        slPrice = Math.round(slPrice * 100.0) / 100.0;

        Map<String, Object> slOrder = new HashMap<>();
        slOrder.put("symbol", orderRequest.getSymbol_id().toUpperCase());
        slOrder.put("qty", orderRequest.getQuantity());
        slOrder.put("side", orderRequest.getAction().equalsIgnoreCase("buy") ? "sell" : "buy");
        slOrder.put("type", "stop");
        slOrder.put("stop_price", slPrice);
        slOrder.put("time_in_force", "gtc");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(slOrder, headers);

        // Placing the Stop Loss order
        restTemplate.postForEntity(ordersUrl, entity, Map.class);

        // Monitor stop loss and target
        monitorOrders(slOrder, headers);
    }

    // Set up Target Order based on the filled price
    private void setTargetOrder(OrderRequest orderRequest, HttpHeaders headers, String executionPrice) {
        String ordersUrl = url + "/orders";

        // Calculating Target price
        double targetPrice = Double.parseDouble(executionPrice) * (1 + (orderRequest.getTarget_percentage() / 100));
        targetPrice = Math.round(targetPrice * 100.0) / 100.0;

        Map<String, Object> targetOrder = new HashMap<>();
        targetOrder.put("symbol", orderRequest.getSymbol_id().toUpperCase());
        targetOrder.put("qty", orderRequest.getQuantity());
        targetOrder.put("side", orderRequest.getAction().equalsIgnoreCase("buy") ? "sell" : "buy");
        targetOrder.put("type", "limit");
        targetOrder.put("limit_price", targetPrice);
        targetOrder.put("time_in_force", "gtc");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(targetOrder, headers);

        // Placing the Target order
        restTemplate.postForEntity(ordersUrl, entity, Map.class);

        // Monitor stop loss and target
        monitorOrders(targetOrder, headers);
    }

    // Monitor the Stop Loss and Target Orders
    private void monitorOrders(Map<String, Object> order, HttpHeaders headers) {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                // Logic to monitor both Stop Loss and Target orders by fetching order status
                // from Alpaca API and canceling the other order if one is triggered.

                String ordersUrl = url + "/orders/" + order.get("id");

                ResponseEntity<Map> response = restTemplate.exchange(
                        ordersUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

                if (response.getStatusCode() == HttpStatus.OK) {
                    Map<String, Object> body = response.getBody();
                    String status = (String) body.get("status");

                    if ("filled".equalsIgnoreCase(status)) {
                        log.info("Order with ID " + order.get("id") + " is filled. Canceling the other order.");
                        cancelOrder(headers, order.get("id").toString());
                        // Stop monitoring after filling
                        timer.cancel();
                    }
                }
            }
        }, 0, 1000);
    }

    // Cancel an order if needed
    private void cancelOrder(HttpHeaders headers, String orderId) {
        String cancelUrl = url + "/orders/" + orderId;
        restTemplate.exchange(cancelUrl, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class);
        log.info("Order with ID " + orderId + " has been canceled.");
    }
}
