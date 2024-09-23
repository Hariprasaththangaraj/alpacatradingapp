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

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Value("${alpaca.api.key}")
    private String key;

    @Value("${alpaca.api.secret}")
    private String secret;

    @Value("${alpaca.base.url}")
    private String url;

    @Value("${alpaca.data.url}")
    private String currentData;

    @Autowired
    private final RestTemplate restTemplate;

    public OrderServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public Map<String, Object> placeOrder(OrderRequest orderRequest) {

        String marketDataUrl = currentData + "/v2/stocks/" + orderRequest.getSymbol_id().toUpperCase() + "/trades/latest";
        String checkMarketStatusUrl = url + "/clock";
        ResponseEntity<Map> response = null;

        // Setting API secret Key
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("APCA-API-KEY-ID", key);
        headers.set("APCA-API-SECRET-KEY", secret);

        //   checkingCurrentMarketStatus(checkMarketStatusUrl, headers);

        double currentStockPrice = getCurrentStockPrice(marketDataUrl, headers);

        //placing order
        placeOrder(orderRequest, currentStockPrice, headers);
        return (Map<String, Object>) response.getBody();
    }

    private double getCurrentStockPrice(String marketDataUrl, HttpHeaders headers) {
        // Get the current price of the symbol
        ResponseEntity<Map> currentPriceResponse = restTemplate.exchange(marketDataUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        Map<String, Object> tradeInfo = (Map<String, Object>) currentPriceResponse.getBody().get("trade");

        double currentStockPrice = (double) tradeInfo.get("p");
        return currentStockPrice;
    }

    private void checkingCurrentMarketStatus(String checkMarketStatusUrl, HttpHeaders headers) {
        //Checking current market open status
        ResponseEntity<Map> currentMarketStatus = restTemplate.exchange(checkMarketStatusUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        boolean marketStatus = (boolean) currentMarketStatus.getBody().get("is_open");

        if (!marketStatus) throw new RuntimeException("Market Currently Closed Try After Market Hours");
    }

    private ResponseEntity<Map> placeOrder(OrderRequest orderRequest, double currentStockPrice, HttpHeaders headers) {

        String orderSide = orderRequest.getAction().toLowerCase();

        //Place the main order ( buy or sell)
        Map<String, Object> mainOrder = new HashMap<>();
        mainOrder.put("symbol", orderRequest.getSymbol_id().toUpperCase());
        mainOrder.put("qty", orderRequest.getQuantity());
        mainOrder.put("side", orderSide);
        mainOrder.put("type", "market");
        mainOrder.put("time_in_force", "gtc");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(mainOrder, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(url + "/orders", entity, Map.class);

        log.info(orderSide.substring(0, 1).toUpperCase() + orderSide.substring(1) + " Order placed: " + response.getBody());
        if (response.getStatusCode() != HttpStatus.CREATED) {
            throw new RuntimeException("Failed to place the main order: " + response.getBody());
        }

        String mainOrderId = (String) response.getBody().get("id");

        // Wait for the main order to be filled
        if (!waitForOrderFill(mainOrderId, headers)) {
            throw new RuntimeException("Main order not filled within the expected time.");
        }

        //  Calculate the target and stop-loss prices
        double profitTarget;
        double slTarget;

        if ("buy".equals(orderSide)) {
            profitTarget = currentStockPrice + (currentStockPrice * orderRequest.getTarget_percentage() / 100);
            slTarget = currentStockPrice - (currentStockPrice * orderRequest.getSl_percentage() / 100);
        } else {
            profitTarget = currentStockPrice - (currentStockPrice * orderRequest.getTarget_percentage() / 100);
            slTarget = currentStockPrice + (currentStockPrice * orderRequest.getSl_percentage() / 100);
        }

        profitTarget = roundToNearestCent(profitTarget);
        slTarget = roundToNearestCent(slTarget);

        //   Place the target and stop-loss orders after the main order is filled
        placeTargetAndStopLossOrders(orderRequest, headers, profitTarget, slTarget, orderSide);

        // Start monitoring the status of both target and stop-loss orders
        monitorOrderStatus(mainOrderId, headers);

        return response;
    }

    // Method to wait for the main order to be filled before placing the target/stop-loss orders
    private boolean waitForOrderFill(String orderId, HttpHeaders headers) {
        boolean isFilled = false;
        int retryCount = 0;
        // Retry for 10 times
        while (!isFilled && retryCount < 10) {
            try {
                // Check the order status via API
                ResponseEntity<Map> response = restTemplate.getForEntity(url + "/v2/orders/" + orderId, Map.class, headers);
                String status = (String) response.getBody().get("status");

                if ("filled".equalsIgnoreCase(status)) {
                    log.info("Main order filled: " + orderId);
                    isFilled = true;
                } else {
                    log.info("Waiting for the main order to be filled... Retry count: " + retryCount);
                    // Wait for 1 seconds before retrying
                    Thread.sleep(1000);
                    retryCount++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Error while waiting for the order to be filled: " + e.getMessage());
                break;
            }
        }
        return isFilled;
    }

    // Place target and stop-loss orders after the main order is filled
    private void placeTargetAndStopLossOrders(OrderRequest orderRequest, HttpHeaders headers, double profitTarget, double slTarget, String orderSide) {

        // Place the target order
        Map<String, Object> targetOrder = new HashMap<>();
        targetOrder.put("symbol", orderRequest.getSymbol_id().toUpperCase());
        targetOrder.put("qty", orderRequest.getQuantity());
        targetOrder.put("side", orderSide.equals("buy") ? "sell" : "buy");
        targetOrder.put("type", "limit");
        targetOrder.put("limit_price", profitTarget);
        targetOrder.put("time_in_force", "gtc");

        HttpEntity<Map<String, Object>> targetEntity = new HttpEntity<>(targetOrder, headers);
        ResponseEntity<Map> targetResponse = restTemplate.postForEntity(url + "/v2/orders", targetEntity, Map.class);
        log.info("Target Order placed: " + targetResponse.getBody());

        // Place the stop-loss order
        Map<String, Object> stopLossOrder = new HashMap<>();
        stopLossOrder.put("symbol", orderRequest.getSymbol_id().toUpperCase());
        stopLossOrder.put("qty", orderRequest.getQuantity());
        stopLossOrder.put("side", orderSide.equals("buy") ? "sell" : "buy"); // opposite side
        stopLossOrder.put("type", "stop");
        stopLossOrder.put("stop_price", slTarget);
        stopLossOrder.put("time_in_force", "gtc");

        HttpEntity<Map<String, Object>> stopLossEntity = new HttpEntity<>(stopLossOrder, headers);
        ResponseEntity<Map> stopLossResponse = restTemplate.postForEntity(url + "/v2/orders", stopLossEntity, Map.class);
        log.info("Stop Loss Order placed: " + stopLossResponse.getBody());
    }

    // Method to monitor target and stop-loss orders and cancel the other once one is triggered
    private void monitorOrderStatus(String mainOrderId, HttpHeaders headers) {

        boolean isMonitoring = true;

        while (isMonitoring) {
            // Fetch both target and stop-loss order statuses
            String targetOrderStatus = getOrderStatus(mainOrderId, "target", headers);
            String stopLossOrderStatus = getOrderStatus(mainOrderId, "stop-loss", headers);

            // If target is filled, cancel stop-loss
            if ("filled".equalsIgnoreCase(targetOrderStatus)) {
                cancelOrder(mainOrderId, "stop-loss", headers);
                log.info("Target hit. Stop-loss order cancelled.");
                isMonitoring = false;
            }

            // If stop-loss is filled, cancel target
            if ("filled".equalsIgnoreCase(stopLossOrderStatus)) {
                cancelOrder(mainOrderId, "target", headers);
                log.info("Stop-loss hit. Target order cancelled.");
                isMonitoring = false;
            }

            // Add a delay before checking again (e.g., 5 seconds)
            try {
                Thread.sleep(5000); // 5-second delay
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Error during order monitoring: " + e.getMessage());
            }
        }
    }

    // Method to get the status of a specific order (target or stop-loss)
    private String getOrderStatus(String mainOrderId, String orderType, HttpHeaders headers) {
        // Fetch the order status from the API
        ResponseEntity<Map> response = restTemplate.getForEntity(url + "/v2/orders/" + mainOrderId + "/" + orderType, Map.class, headers);
        return (String) response.getBody().get("status");
    }

    // Method to cancel a specific order (target or stop-loss)
    private void cancelOrder(String mainOrderId, String orderType, HttpHeaders headers) {
        restTemplate.delete(url + "/v2/orders/" + mainOrderId + "/" + orderType, headers);
        log.info(orderType + " order cancelled.");
    }

    // Rounding function to ensure prices are valid
    private double roundToNearestCent(double price) {
        return Math.round(price * 100.0) / 100.0;
    }

}
