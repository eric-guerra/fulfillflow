package com.ericguerra.fulfillflow.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "shipment_status_events")
public class StatusEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id")
    private Shipment shipment;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 12)
    private OrderStatus from;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 12)
    private OrderStatus to;

    @Column(length = 200)
    private String reason;

    @Column(nullable = false)
    private Instant at;

    protected StatusEvent() {}

    StatusEvent(Shipment shipment, OrderStatus from, OrderStatus to, String reason, Instant at) {
        this.shipment = shipment;
        this.from = from;
        this.to = to;
        this.reason = reason;
        this.at = at;
    }

    public OrderStatus getFrom() { return from; }
    public OrderStatus getTo() { return to; }
    public String getReason() { return reason; }
    public Instant getAt() { return at; }
}
