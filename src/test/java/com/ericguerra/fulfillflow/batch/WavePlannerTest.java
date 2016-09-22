package com.ericguerra.fulfillflow.batch;

import com.ericguerra.fulfillflow.domain.OrderStatus;
import com.ericguerra.fulfillflow.domain.Shipment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WavePlannerTest {
    private static Shipment shipment(String node, String method, Instant promised, Map<String, Integer> lines) {
        Shipment s = new Shipment("O-" + promised.getEpochSecond() + lines.hashCode(), method, 0, 0, "77002", promised);
        lines.forEach(s::addLine);
        s.assignNode(node);
        return s;
    }

    @Test
    void groupsByNodeAndMethodFastestFirstAndRespectsCartLimits() {
        Instant t = Instant.parse("2016-05-01T12:00:00Z");
        var planner = new WavePlanner(2, 10, sku -> sku.substring(0, 1));
        var waves = planner.plan(List.of(
                shipment("DC-KTY", "GROUND", t.plusSeconds(300), Map.of("C1", 1)),
                shipment("DC-KTY", "GROUND", t.plusSeconds(100), Map.of("A1", 2, "B1", 1)),
                shipment("DC-KTY", "GROUND", t.plusSeconds(200), Map.of("A1", 1)),
                shipment("DC-KTY", "OVERNIGHT", t, Map.of("B2", 1)),
                shipment("DC-DAL", "GROUND", t, Map.of("A9", 9)),
                shipment("DC-DAL", "GROUND", t.plusSeconds(1), Map.of("A9", 3))));

        assertEquals(List.of("DC-DAL|GROUND", "DC-DAL|GROUND", "DC-KTY|OVERNIGHT", "DC-KTY|GROUND", "DC-KTY|GROUND"),
                waves.stream().map(w -> w.nodeId() + "|" + w.shipMethod()).toList());
        assertEquals(9, waves.get(0).units(), "second Dallas shipment would exceed 10 units");

        var firstGround = waves.get(3);
        assertEquals(2, firstGround.shipments().size(), "max 2 shipments per wave");
        assertEquals(t.plusSeconds(100), firstGround.shipments().get(0).getPromisedBy(), "earliest promise first");
        assertEquals(List.of("A1", "B1"), List.copyOf(firstGround.pickList().keySet()), "pick list in bin order");
        assertEquals(3, firstGround.pickList().get("A1"), "units aggregated across shipments");
    }

    @Test
    void lifecycleRules() {
        assertTrue(OrderStatus.RECEIVED.canMoveTo(OrderStatus.ALLOCATED));
        assertTrue(OrderStatus.WAVED.canMoveTo(OrderStatus.ALLOCATED), "short picks go back to allocation");
        assertFalse(OrderStatus.PACKED.isCancellable());
        assertTrue(OrderStatus.DELIVERED.isTerminal());
        Shipment s = shipment("DC-KTY", "GROUND", Instant.now(), Map.of("A", 1));
        assertThrows(IllegalStateException.class, () -> s.transition(OrderStatus.SHIPPED, "skip", Instant.now()));
    }
}
