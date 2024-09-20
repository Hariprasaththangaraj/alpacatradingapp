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

        checkingCurrentMarketStatus(checkMarketStatusUrl, headers);

        double currentStockPrice = getCurrentStockPrice(marketDataUrl, headers);

        //If Order Type is buy
        if (orderRequest.getAction().equalsIgnoreCase("buy")) {
            response = placingBuyOrder(orderRequest, currentStockPrice, headers, response);
        }

        // If Order Type is sell
        if (orderRequest.getAction().equalsIgnoreCase("sell")) {
            response = placingSellOrder(orderRequest, currentStockPrice, headers, response);
        }
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

    private ResponseEntity<Map> placingBuyOrder(OrderRequest orderRequest, double currentStockPrice, HttpHeaders headers, ResponseEntity<Map> response) {

        //Calculating SL and Profit Target
        double profitTarget = currentStockPrice + (currentStockPrice * orderRequest.getTarget_percentage() / 100);
        double slTarget = currentStockPrice - (currentStockPrice * orderRequest.getSl_percentage() / 100);

        profitTarget = roundToNearestCent(profitTarget);
        slTarget = roundToNearestCent(slTarget);

        // Prepare the bracket order with stop-loss and take-profit
        Map<String, Object> order = new HashMap<>();
        order.put("symbol", orderRequest.getSymbol_id().toUpperCase());
        order.put("qty", orderRequest.getQuantity());
        order.put("side", orderRequest.getAction().toLowerCase());
        order.put("type", "market");
        order.put("time_in_force", "gtc");

        // Bracket order with take-profit and stop-loss
        Map<String, Object> takeProfit = new HashMap<>();
        takeProfit.put("limit_price", profitTarget);
        Map<String, Object> stopLoss = new HashMap<>();
        stopLoss.put("stop_price", slTarget);

        order.put("order_class", "bracket");
        order.put("take_profit", takeProfit);
        order.put("stop_loss", stopLoss);

        // Send the order to Alpaca
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(order, headers);
        response = restTemplate.postForEntity(url + "/orders", entity, Map.class);

        log.info("Buy Order placed " + response);
        if (response.getStatusCode() != HttpStatus.OK) {
            throw new RuntimeException("Failed to place bracket order");
        }
        return response;
    }


    private ResponseEntity<Map> placingSellOrder(OrderRequest orderRequest, double currentStockPrice, HttpHeaders headers, ResponseEntity<Map> response) {

        // Calculating SL and Profit Target
        double profitTarget = currentStockPrice - (currentStockPrice * orderRequest.getTarget_percentage() / 100);
        double slTarget = currentStockPrice + (currentStockPrice * orderRequest.getSl_percentage() / 100);

        profitTarget = roundToNearestCent(profitTarget);
        slTarget = roundToNearestCent(slTarget);

        // Prepare the bracket order with stop-loss and take-profit
        Map<String, Object> order = new HashMap<>();
        order.put("symbol", orderRequest.getSymbol_id().toUpperCase());
        order.put("qty", orderRequest.getQuantity());
        order.put("side", orderRequest.getAction().toLowerCase());
        order.put("type", "market");
        order.put("time_in_force", "gtc");

        // Bracket order with take-profit and stop-loss
        Map<String, Object> takeProfit = new HashMap<>();
        takeProfit.put("limit_price", profitTarget);
        Map<String, Object> stopLoss = new HashMap<>();
        stopLoss.put("stop_price", slTarget);

        order.put("order_class", "bracket");
        order.put("take_profit", takeProfit);
        order.put("stop_loss", stopLoss);

        // Send the order to Alpaca
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(order, headers);
        response = restTemplate.postForEntity(url + "/orders", entity, Map.class);

        log.info("Sell Order placed " + response);
        if (response.getStatusCode() != HttpStatus.OK) {
            throw new RuntimeException("Failed to place bracket sell order");
        }

        return response;
    }

    // Rounding function to ensure prices are valid
    private double roundToNearestCent(double price) {
        return Math.round(price * 100.0) / 100.0;
    }
}
