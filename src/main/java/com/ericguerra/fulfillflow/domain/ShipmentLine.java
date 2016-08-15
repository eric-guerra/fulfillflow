package com.ericguerra.fulfillflow.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "shipment_lines")
public class ShipmentLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id")
    private Shipment shipment;

    @Column(nullable = false, length = 40)
    private String sku;

    @Column(nullable = false)
    private int qty;

    @Column(name = "picked_qty", nullable = false)
    private int pickedQty;

    protected ShipmentLine() {}

    ShipmentLine(Shipment shipment, String sku, int qty) {
        this.shipment = shipment;
        this.sku = sku;
        this.qty = qty;
    }

    public void recordPick(int qty) {
        if (qty < 0 || qty > this.qty) throw new IllegalArgumentException("picked " + qty + " of " + this.qty);
        this.pickedQty = qty;
    }

    public boolean isShort() {
        return pickedQty < qty;
    }

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public int getQty() { return qty; }
    public int getPickedQty() { return pickedQty; }
}
