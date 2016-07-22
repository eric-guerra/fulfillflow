package com.ericguerra.fulfillflow.service;

import java.time.Instant;
import java.util.Map;

/** Inbound message from the commerce platform (orders.placed). */
public record OrderPlaced(String orderId, Map<String, Integer> lines, String shipMethod,
                          double destLat, double destLng, String destZip, Instant placedAt) {
    public OrderPlaced {
        if (orderId == null || orderId.isBlank()) throw new IllegalArgumentException("orderId is required");
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("order has no lines");
        lines.forEach((sku, qty) -> {
            if (qty == null || qty <= 0) throw new IllegalArgumentException("invalid qty for " + sku);
        });
        if (!java.util.Set.of("GROUND", "TWO_DAY", "OVERNIGHT").contains(shipMethod)) throw new IllegalArgumentException("unknown ship method " + shipMethod);
    }

    public int promisedDays() {
        return switch (shipMethod) {
            case "OVERNIGHT" -> 1;
            case "TWO_DAY" -> 2;
            default -> 5;
        };
    }
}
