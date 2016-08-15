package com.ericguerra.fulfillflow.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The unit of fulfillment: lines from one order shipped together from one node. */
@Entity
@Table(name = "shipments")
public class Shipment {
    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(name = "order_id", nullable = false, length = 40)
    private String orderId;

    @Column(name = "node_id", length = 10)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private OrderStatus status = OrderStatus.RECEIVED;

    @Column(name = "ship_method", nullable = false, length = 12)
    private String shipMethod;

    @Column(name = "dest_lat", nullable = false) private double destLat;
    @Column(name = "dest_lng", nullable = false) private double destLng;
    @Column(name = "dest_zip", nullable = false, length = 10) private String destZip;

    @Column(name = "wave_id", length = 36)
    private String waveId;

    @Column(name = "tracking_number", length = 40)
    private String trackingNumber;

    @Column(name = "promised_by", nullable = false)
    private Instant promisedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    // Both collections are needed wherever a shipment is used (API, batch). Separate selects avoid
    // Hibernate's MultipleBagFetchException that two join-fetched lists would cause.
    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @OrderBy("id")
    private List<ShipmentLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @OrderBy("at")
    private List<StatusEvent> history = new ArrayList<>();

    protected Shipment() {}

    public Shipment(String orderId, String shipMethod, double destLat, double destLng, String destZip, Instant promisedBy) {
        this.orderId = orderId;
        this.shipMethod = shipMethod;
        this.destLat = destLat;
        this.destLng = destLng;
        this.destZip = destZip;
        this.promisedBy = promisedBy;
    }

    public void addLine(String sku, int qty) {
        lines.add(new ShipmentLine(this, sku, qty));
    }

    /** Enforces the lifecycle; every transition is recorded with a reason. */
    public void transition(OrderStatus next, String reason, Instant at) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("cannot move shipment " + id + " from " + status + " to " + next);
        }
        history.add(new StatusEvent(this, status, next, reason, at));
        status = next;
    }

    public void assignNode(String nodeId) { this.nodeId = nodeId; }
    public void assignWave(String waveId) { this.waveId = waveId; }
    public void setTrackingNumber(String t) { this.trackingNumber = t; }

    public String getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getNodeId() { return nodeId; }
    public OrderStatus getStatus() { return status; }
    public String getShipMethod() { return shipMethod; }
    public double getDestLat() { return destLat; }
    public double getDestLng() { return destLng; }
    public String getDestZip() { return destZip; }
    public String getWaveId() { return waveId; }
    public String getTrackingNumber() { return trackingNumber; }
    public Instant getPromisedBy() { return promisedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public List<ShipmentLine> getLines() { return lines; }
    public List<StatusEvent> getHistory() { return history; }
}
