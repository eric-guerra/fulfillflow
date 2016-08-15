package com.ericguerra.fulfillflow.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Fulfillment lifecycle for a shipment (an order may split into several shipments).
 *
 * RECEIVED → ALLOCATED → WAVED → PICKED → PACKED → SHIPPED → DELIVERED
 *        ↘ BACKORDERED → ALLOCATED            (any pre-ship state) → CANCELLED
 */
public enum OrderStatus {
    RECEIVED, BACKORDERED, ALLOCATED, WAVED, PICKED, PACKED, SHIPPED, DELIVERED, CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> NEXT = Map.of(
            RECEIVED, EnumSet.of(ALLOCATED, BACKORDERED, CANCELLED),
            BACKORDERED, EnumSet.of(ALLOCATED, CANCELLED),
            ALLOCATED, EnumSet.of(WAVED, CANCELLED),
            WAVED, EnumSet.of(PICKED, ALLOCATED, CANCELLED), // back to ALLOCATED if a pick shorts
            PICKED, EnumSet.of(PACKED, CANCELLED),
            PACKED, EnumSet.of(SHIPPED),
            SHIPPED, EnumSet.of(DELIVERED),
            DELIVERED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    public boolean canMoveTo(OrderStatus next) {
        return NEXT.get(this).contains(next);
    }

    public boolean isTerminal() {
        return NEXT.get(this).isEmpty();
    }

    /** Once boxed and labeled, a shipment can't be cancelled in the warehouse. */
    public boolean isCancellable() {
        return canMoveTo(CANCELLED);
    }
}
