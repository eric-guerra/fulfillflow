package com.ericguerra.fulfillflow.allocation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AllocationEngineTest {
    private final AllocationEngine engine = new AllocationEngine();
    // Customer in Houston.
    private static final double LAT = 29.76, LNG = -95.37;

    private static final AllocationEngine.Node KATY_DC = new AllocationEngine.Node("DC-KTY", false, 29.7858, -95.8245, 100);
    private static final AllocationEngine.Node DALLAS_DC = new AllocationEngine.Node("DC-DAL", false, 32.8998, -97.0403, 100);
    private static final AllocationEngine.Node HOU_STORE = new AllocationEngine.Node("ST-HOU1", true, 29.7390, -95.4637, 10);
    private static final List<AllocationEngine.Node> NODES = List.of(DALLAS_DC, HOU_STORE, KATY_DC);

    @Test
    void prefersSingleNodeAndDcOverCloserStore() {
        var stock = Map.of(
                "DC-KTY", Map.of("A", 5, "B", 5),
                "DC-DAL", Map.of("A", 5, "B", 5),
                "ST-HOU1", Map.of("A", 5, "B", 5));
        var r = engine.allocate(Map.of("A", 1, "B", 2), NODES, stock, LAT, LNG);
        assertFalse(r.isSplit());
        assertTrue(r.isComplete());
        assertEquals("DC-KTY", r.allocations().get(0).nodeId(), "DC preferred over store; Katy closer than Dallas");
    }

    @Test
    void usesStoreWhenItIsTheOnlySingleNodeOption() {
        var stock = Map.of("DC-KTY", Map.of("A", 1), "ST-HOU1", Map.of("A", 1, "B", 1));
        var r = engine.allocate(Map.of("A", 1, "B", 1), NODES, stock, LAT, LNG);
        assertEquals("ST-HOU1", r.allocations().get(0).nodeId());
        assertFalse(r.isSplit(), "one box from a store beats two boxes");
    }

    @Test
    void splitsGreedilyByCoverageThenBackorders() {
        var stock = Map.of(
                "DC-KTY", Map.of("A", 3),
                "DC-DAL", Map.of("A", 1, "B", 4),
                "ST-HOU1", Map.of("C", 1));
        var r = engine.allocate(Map.of("A", 4, "B", 4, "C", 2), NODES, stock, LAT, LNG);
        assertTrue(r.isSplit());
        // Dallas covers the most units (1 A + 4 B = 5), then Katy (3 A), then the store (1 C).
        assertEquals(List.of("DC-DAL", "DC-KTY", "ST-HOU1"), r.allocations().stream().map(AllocationEngine.Allocation::nodeId).toList());
        assertEquals(Map.of("A", 4, "B", 4, "C", 1), AllocationEngine.totals(r.allocations()));
        assertEquals(Map.of("C", 1), r.backordered());
    }

    @Test
    void nodesAtCapacityAreSkipped() {
        var full = new AllocationEngine.Node("DC-KTY", false, 29.7858, -95.8245, 0);
        var stock = Map.of("DC-KTY", Map.of("A", 9), "DC-DAL", Map.of("A", 9));
        var r = engine.allocate(Map.of("A", 1), List.of(full, DALLAS_DC), stock, LAT, LNG);
        assertEquals("DC-DAL", r.allocations().get(0).nodeId());
    }

    @Test
    void nothingAvailableMeansFullBackorder() {
        var r = engine.allocate(Map.of("Z", 2), NODES, Map.of(), LAT, LNG);
        assertTrue(r.allocations().isEmpty());
        assertEquals(Map.of("Z", 2), r.backordered());
    }
}
