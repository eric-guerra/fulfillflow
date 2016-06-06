package com.ericguerra.fulfillflow.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** A batch of shipments released to the warehouse floor together, grouped by node and ship method. */
@Entity
@Table(name = "pick_waves")
public class PickWave {
    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(name = "node_id", nullable = false, length = 10)
    private String nodeId;

    @Column(name = "ship_method", nullable = false, length = 12)
    private String shipMethod;

    @Column(name = "shipment_count", nullable = false)
    private int shipmentCount;

    @Column(name = "unit_count", nullable = false)
    private int unitCount;

    /** Pick path: SKUs in bin order, aggregated across the wave's shipments ("pick then sort"). */
    @Column(name = "pick_list", columnDefinition = "text", nullable = false)
    private String pickList;

    @Column(name = "job_execution_id")
    private Long jobExecutionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PickWave() {}

    public PickWave(String nodeId, String shipMethod, int shipmentCount, int unitCount, String pickList, Long jobExecutionId, Instant createdAt) {
        this.nodeId = nodeId;
        this.shipMethod = shipMethod;
        this.shipmentCount = shipmentCount;
        this.unitCount = unitCount;
        this.pickList = pickList;
        this.jobExecutionId = jobExecutionId;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getNodeId() { return nodeId; }
    public String getShipMethod() { return shipMethod; }
    public int getShipmentCount() { return shipmentCount; }
    public int getUnitCount() { return unitCount; }
    public String getPickList() { return pickList; }
    public Long getJobExecutionId() { return jobExecutionId; }
    public Instant getCreatedAt() { return createdAt; }
}
