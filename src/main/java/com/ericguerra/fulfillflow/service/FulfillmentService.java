package com.ericguerra.fulfillflow.service;

import com.ericguerra.fulfillflow.allocation.AllocationEngine;
import com.ericguerra.fulfillflow.domain.FulfillmentNode;
import com.ericguerra.fulfillflow.domain.NodeStock;
import com.ericguerra.fulfillflow.domain.OrderStatus;
import com.ericguerra.fulfillflow.domain.Repositories.NodeRepository;
import com.ericguerra.fulfillflow.domain.Repositories.NodeStockRepository;
import com.ericguerra.fulfillflow.domain.Repositories.ShipmentRepository;
import com.ericguerra.fulfillflow.domain.Shipment;
import com.ericguerra.fulfillflow.domain.ShipmentLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class FulfillmentService {
    private final ShipmentRepository shipments;
    private final NodeRepository nodes;
    private final NodeStockRepository stock;
    private final StatusPublisher publisher;
    private final AllocationEngine engine = new AllocationEngine();
    private final Clock clock;

    public FulfillmentService(ShipmentRepository shipments, NodeRepository nodes, NodeStockRepository stock,
                              StatusPublisher publisher, Clock clock) {
        this.shipments = shipments;
        this.nodes = nodes;
        this.stock = stock;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Accepts an order and allocates it. Idempotent on orderId (message redelivery).
     * Stock rows for the order's SKUs are locked for the duration so concurrent orders can't double-allocate.
     */
    @Transactional
    public List<Shipment> receive(OrderPlaced order) {
        if (shipments.existsByOrderId(order.orderId())) return shipments.findByOrderIdOrderByCreatedAt(order.orderId());
        Instant now = clock.instant();
        Instant promised = now.plus(Duration.ofDays(order.promisedDays()));

        List<NodeStock> locked = stock.lockBySkus(order.lines().keySet());
        Map<String, Map<String, Integer>> byNode = new HashMap<>();
        Map<String, NodeStock> rows = new HashMap<>();
        for (NodeStock s : locked) {
            byNode.computeIfAbsent(s.getNodeId(), k -> new HashMap<>()).put(s.getSku(), s.getAvailable());
            rows.put(s.getNodeId() + "|" + s.getSku(), s);
        }

        Map<String, Long> loadToday = loadToday(now);
        List<AllocationEngine.Node> candidates = nodes.findAll().stream()
                .map(n -> new AllocationEngine.Node(n.getId(), n.getType() == FulfillmentNode.Type.STORE, n.getLat(), n.getLng(),
                        (int) Math.max(0, n.getDailyCapacity() - loadToday.getOrDefault(n.getId(), 0L))))
                .toList();

        var result = engine.allocate(order.lines(), candidates, byNode, order.destLat(), order.destLng());
        List<Shipment> created = new ArrayList<>();
        for (var alloc : result.allocations()) {
            Shipment s = newShipment(order, promised);
            alloc.lines().forEach((sku, qty) -> {
                s.addLine(sku, qty);
                rows.get(alloc.nodeId() + "|" + sku).take(qty);
            });
            s.assignNode(alloc.nodeId());
            s.transition(OrderStatus.ALLOCATED, result.isSplit() ? "split across " + result.allocations().size() + " nodes" : "allocated", now);
            created.add(shipments.save(s));
        }
        if (!result.backordered().isEmpty()) {
            Shipment s = newShipment(order, promised);
            result.backordered().forEach(s::addLine);
            s.transition(OrderStatus.BACKORDERED, "no node has " + result.backordered(), now);
            created.add(shipments.save(s));
        }
        created.forEach(this::publishAfterCommit);
        return created;
    }

    private Shipment newShipment(OrderPlaced order, Instant promised) {
        return new Shipment(order.orderId(), order.shipMethod(), order.destLat(), order.destLng(), order.destZip(), promised);
    }

    private Map<String, Long> loadToday(Instant now) {
        Instant dayStart = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, Long> load = new HashMap<>();
        for (Object[] row : shipments.loadByNodeSince(dayStart)) load.put((String) row[0], (Long) row[1]);
        return load;
    }

    /** Re-runs allocation for backordered shipments when new stock arrives. */
    @Transactional
    public int retryBackorders() {
        int allocated = 0;
        for (Shipment s : shipments.findByStatus(OrderStatus.BACKORDERED)) {
            Map<String, Integer> lines = new HashMap<>();
            s.getLines().forEach(l -> lines.put(l.getSku(), l.getQty()));
            List<NodeStock> locked = stock.lockBySkus(lines.keySet());
            Map<String, Map<String, Integer>> byNode = new HashMap<>();
            locked.forEach(ns -> byNode.computeIfAbsent(ns.getNodeId(), k -> new HashMap<>()).put(ns.getSku(), ns.getAvailable()));
            var candidates = nodes.findAll().stream()
                    .map(n -> new AllocationEngine.Node(n.getId(), n.getType() == FulfillmentNode.Type.STORE, n.getLat(), n.getLng(), Integer.MAX_VALUE))
                    .toList();
            var result = engine.allocate(lines, candidates, byNode, s.getDestLat(), s.getDestLng());
            // Only release a backorder when it can ship complete from one node; partial re-splits wait.
            if (result.isComplete() && !result.isSplit()) {
                var alloc = result.allocations().get(0);
                locked.stream().filter(ns -> ns.getNodeId().equals(alloc.nodeId()))
                        .forEach(ns -> ns.take(alloc.lines().getOrDefault(ns.getSku(), 0)));
                s.assignNode(alloc.nodeId());
                s.transition(OrderStatus.ALLOCATED, "backorder filled", clock.instant());
                publishAfterCommit(s);
                allocated++;
            }
        }
        return allocated;
    }

    @Transactional
    public Shipment recordPick(String shipmentId, Map<String, Integer> picked) {
        Shipment s = get(shipmentId);
        for (ShipmentLine l : s.getLines()) l.recordPick(picked.getOrDefault(l.getSku(), 0));
        if (s.getLines().stream().anyMatch(ShipmentLine::isShort)) {
            // Short pick: the bin was wrong. Return to allocation so it can be re-waved or re-sourced.
            s.assignWave(null);
            s.transition(OrderStatus.ALLOCATED, "short pick", clock.instant());
        } else {
            s.transition(OrderStatus.PICKED, "picked", clock.instant());
        }
        publishAfterCommit(s);
        return s;
    }

    @Transactional
    public Shipment pack(String shipmentId) {
        Shipment s = get(shipmentId);
        s.transition(OrderStatus.PACKED, "packed", clock.instant());
        publishAfterCommit(s);
        return s;
    }

    @Transactional
    public Shipment ship(String shipmentId, String trackingNumber) {
        Shipment s = get(shipmentId);
        s.setTrackingNumber(trackingNumber != null ? trackingNumber : generateTracking(s));
        s.transition(OrderStatus.SHIPPED, "handed to carrier", clock.instant());
        publishAfterCommit(s);
        return s;
    }

    /** Cancels every not-yet-packed shipment of an order and returns its stock. */
    @Transactional
    public List<Shipment> cancel(String orderId, String reason) {
        List<Shipment> list = shipments.findByOrderIdOrderByCreatedAt(orderId);
        if (list.isEmpty()) throw new NoSuchElementException("order " + orderId);
        List<Shipment> cancelled = new ArrayList<>();
        for (Shipment s : list) {
            if (!s.getStatus().isCancellable()) continue;
            if (s.getNodeId() != null && s.getStatus() != OrderStatus.BACKORDERED) {
                for (ShipmentLine l : s.getLines()) {
                    stock.findById(new NodeStock.Key(s.getNodeId(), l.getSku())).ifPresent(ns -> ns.giveBack(l.getQty()));
                }
            }
            s.transition(OrderStatus.CANCELLED, reason, clock.instant());
            publishAfterCommit(s);
            cancelled.add(s);
        }
        return cancelled;
    }

    public Shipment get(String id) {
        return shipments.findById(id).orElseThrow(() -> new NoSuchElementException("shipment " + id));
    }

    private static String generateTracking(Shipment s) {
        return "1Z" + s.getShipMethod().charAt(0) + String.format("%015d", ThreadLocalRandom.current().nextLong(1_000_000_000_000_000L));
    }

    private void publishAfterCommit(Shipment s) {
        var event = new StatusPublisher.StatusChanged(s.getOrderId(), s.getId(), s.getStatus(), s.getTrackingNumber());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publish(event);
                }
            });
        } else {
            publisher.publish(event);
        }
    }
}
