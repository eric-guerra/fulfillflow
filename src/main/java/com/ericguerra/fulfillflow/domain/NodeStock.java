package com.ericguerra.fulfillflow.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "node_stock")
@IdClass(NodeStock.Key.class)
public class NodeStock {
    public static class Key implements Serializable {
        private String nodeId;
        private String sku;

        public Key() {}

        public Key(String nodeId, String sku) {
            this.nodeId = nodeId;
            this.sku = sku;
        }

        @Override public boolean equals(Object o) { return o instanceof Key k && nodeId.equals(k.nodeId) && sku.equals(k.sku); }
        @Override public int hashCode() { return Objects.hash(nodeId, sku); }
    }

    @Id @Column(name = "node_id", length = 10) private String nodeId;
    @Id @Column(length = 40) private String sku;

    @Column(nullable = false)
    private int available;

    @Version
    private long version;

    protected NodeStock() {}

    public NodeStock(String nodeId, String sku, int available) {
        this.nodeId = nodeId;
        this.sku = sku;
        this.available = available;
    }

    public void take(int qty) {
        if (qty > available) throw new IllegalStateException("only " + available + " " + sku + " at " + nodeId);
        available -= qty;
    }

    public void giveBack(int qty) {
        available += qty;
    }

    public String getNodeId() { return nodeId; }
    public String getSku() { return sku; }
    public int getAvailable() { return available; }
}
