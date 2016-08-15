package com.ericguerra.fulfillflow.domain;

import jakarta.persistence.*;

/** A distribution center or ship-from-store location. */
@Entity
@Table(name = "nodes")
public class FulfillmentNode {
    public enum Type { DC, STORE }

    @Id
    @Column(length = 10)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Type type;

    @Column(nullable = false) private double lat;
    @Column(nullable = false) private double lng;

    /** Orders per day the node can process; ship-from-store nodes are much smaller. */
    @Column(name = "daily_capacity", nullable = false)
    private int dailyCapacity;

    /** Local hour after which today's carrier pickup is missed. */
    @Column(name = "cutoff_hour", nullable = false)
    private int cutoffHour;

    protected FulfillmentNode() {}

    public FulfillmentNode(String id, Type type, double lat, double lng, int dailyCapacity, int cutoffHour) {
        this.id = id;
        this.type = type;
        this.lat = lat;
        this.lng = lng;
        this.dailyCapacity = dailyCapacity;
        this.cutoffHour = cutoffHour;
    }

    public String getId() { return id; }
    public Type getType() { return type; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public int getDailyCapacity() { return dailyCapacity; }
    public int getCutoffHour() { return cutoffHour; }
}
