package com.ericguerra.fulfillflow;

import com.ericguerra.fulfillflow.domain.NodeStock;
import com.ericguerra.fulfillflow.domain.OrderStatus;
import com.ericguerra.fulfillflow.domain.Repositories.NodeStockRepository;
import com.ericguerra.fulfillflow.domain.Repositories.PickWaveRepository;
import com.ericguerra.fulfillflow.domain.Repositories.ShipmentRepository;
import com.ericguerra.fulfillflow.domain.Shipment;
import com.ericguerra.fulfillflow.service.FulfillmentService;
import com.ericguerra.fulfillflow.service.OrderPlaced;
import com.ericguerra.fulfillflow.service.StatusPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "fulfillflow.wave.schedule-enabled=false")
@ActiveProfiles("test")
class FulfillmentIntegrationTest {
    static final List<StatusPublisher.StatusChanged> PUBLISHED = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Config {
        @Bean
        StatusPublisher recordingPublisher() {
            return PUBLISHED::add;
        }
    }

    @Autowired FulfillmentService fulfillment;
    @Autowired ShipmentRepository shipments;
    @Autowired NodeStockRepository stock;
    @Autowired PickWaveRepository waves;
    @Autowired JobLauncher launcher;
    @Autowired @Qualifier("waveJob") Job waveJob;
    @Autowired @Qualifier("manifestJob") Job manifestJob;

    @BeforeEach
    void reset() {
        shipments.deleteAll();
        waves.deleteAll();
        stock.deleteAll();
        PUBLISHED.clear();
        stock.saveAll(List.of(
                new NodeStock("DC-KTY", "TENT-2P", 5), new NodeStock("DC-KTY", "STOVE", 10),
                new NodeStock("DC-DAL", "TENT-2P", 5), new NodeStock("DC-DAL", "LANTERN", 2),
                new NodeStock("ST-HOU1", "LANTERN", 1)));
    }

    private static OrderPlaced order(String id, Map<String, Integer> lines) {
        return new OrderPlaced(id, lines, "GROUND", 29.76, -95.37, "77002", Instant.now());
    }

    private int available(String node, String sku) {
        return stock.findById(new NodeStock.Key(node, sku)).map(NodeStock::getAvailable).orElse(0);
    }

    @Test
    void singleNodeAllocationIsIdempotentAndReservesStock() {
        var created = fulfillment.receive(order("O-1", Map.of("TENT-2P", 2, "STOVE", 1)));
        assertEquals(1, created.size());
        assertEquals("DC-KTY", created.get(0).getNodeId());
        assertEquals(OrderStatus.ALLOCATED, created.get(0).getStatus());
        assertEquals(3, available("DC-KTY", "TENT-2P"));

        var again = fulfillment.receive(order("O-1", Map.of("TENT-2P", 2, "STOVE", 1)));
        assertEquals(created.get(0).getId(), again.get(0).getId(), "redelivered message does not double-allocate");
        assertEquals(3, available("DC-KTY", "TENT-2P"));
        assertEquals(1, PUBLISHED.size());
    }

    @Test
    void splitAndBackorderThenRetry() {
        var created = fulfillment.receive(order("O-2", Map.of("STOVE", 1, "LANTERN", 2, "FUEL", 1)));
        assertEquals(3, created.size(), "Katy (stove), Dallas (lanterns), backorder (fuel)");
        Shipment backorder = created.stream().filter(s -> s.getStatus() == OrderStatus.BACKORDERED).findFirst().orElseThrow();

        assertEquals(0, fulfillment.retryBackorders());
        stock.save(new NodeStock("DC-DAL", "FUEL", 4));
        assertEquals(1, fulfillment.retryBackorders());
        Shipment filled = shipments.findById(backorder.getId()).orElseThrow();
        assertEquals(OrderStatus.ALLOCATED, filled.getStatus());
        assertEquals("DC-DAL", filled.getNodeId());
        assertEquals(3, available("DC-DAL", "FUEL"));
    }

    @Test
    void wavePickPackShipAndManifest(@TempDir Path dir) throws Exception {
        fulfillment.receive(order("O-3", Map.of("TENT-2P", 1)));
        fulfillment.receive(order("O-4", Map.of("TENT-2P", 1, "STOVE", 2)));

        var exec = launcher.run(waveJob, new JobParametersBuilder().addLong("ts", System.nanoTime()).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, exec.getStatus());
        var created = waves.findByJobExecutionId(exec.getId());
        assertEquals(1, created.size());
        assertEquals("STOVEx2,TENT-2Px2", created.get(0).getPickList());
        List<Shipment> waved = shipments.findByStatus(OrderStatus.WAVED);
        assertEquals(2, waved.size());

        Shipment s = waved.stream().filter(x -> x.getOrderId().equals("O-4")).findFirst().orElseThrow();
        fulfillment.recordPick(s.getId(), Map.of("TENT-2P", 1, "STOVE", 1));
        assertEquals(OrderStatus.ALLOCATED, fulfillment.get(s.getId()).getStatus(), "short pick returns to allocation");
        assertNull(fulfillment.get(s.getId()).getWaveId());

        Shipment other = waved.stream().filter(x -> x.getOrderId().equals("O-3")).findFirst().orElseThrow();
        fulfillment.recordPick(other.getId(), Map.of("TENT-2P", 1));
        fulfillment.pack(other.getId());
        Shipment shipped = fulfillment.ship(other.getId(), null);
        assertTrue(shipped.getTrackingNumber().startsWith("1ZG"));
        assertEquals(List.of(OrderStatus.ALLOCATED, OrderStatus.WAVED, OrderStatus.PICKED, OrderStatus.PACKED, OrderStatus.SHIPPED),
                fulfillment.get(other.getId()).getHistory().stream().map(h -> h.getTo()).toList());
        assertThrows(IllegalStateException.class, () -> fulfillment.pack(other.getId()));

        Path out = dir.resolve("manifest.csv");
        var manifest = launcher.run(manifestJob, new JobParametersBuilder().addString("output", out.toString()).addLong("ts", System.nanoTime()).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, manifest.getStatus());
        List<String> lines = Files.readAllLines(out);
        assertEquals("tracking_number,shipment_id,order_id,origin_node,service,dest_zip,units", lines.get(0));
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).endsWith(",O-3,DC-KTY,GROUND,77002,1"));
    }

    @Test
    void cancelReturnsStockButNotForPackedShipments() {
        var created = fulfillment.receive(order("O-5", Map.of("TENT-2P", 3)));
        assertEquals(2, available("DC-KTY", "TENT-2P"));
        var cancelled = fulfillment.cancel("O-5", "customer changed mind");
        assertEquals(1, cancelled.size());
        assertEquals(5, available("DC-KTY", "TENT-2P"));
        assertEquals(OrderStatus.CANCELLED, fulfillment.get(created.get(0).getId()).getStatus());
        assertTrue(fulfillment.cancel("O-5", "again").isEmpty(), "already cancelled");
    }

    @Test
    void invalidOrdersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> order("O-6", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new OrderPlaced("O-7", Map.of("A", 1), "TELEPORT", 0, 0, "1", Instant.now()));
    }
}
