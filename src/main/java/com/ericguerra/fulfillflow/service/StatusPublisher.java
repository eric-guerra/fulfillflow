package com.ericguerra.fulfillflow.service;

import com.ericguerra.fulfillflow.domain.OrderStatus;

/** Publishes shipment status changes so the storefront can email "your order shipped". */
public interface StatusPublisher {
    record StatusChanged(String orderId, String shipmentId, OrderStatus status, String trackingNumber) {}

    void publish(StatusChanged event);
}
