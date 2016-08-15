package com.ericguerra.fulfillflow.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public final class Repositories {
    private Repositories() {}

    public interface ShipmentRepository extends JpaRepository<Shipment, String> {
        List<Shipment> findByOrderIdOrderByCreatedAt(String orderId);

        List<Shipment> findByStatus(OrderStatus status);

        boolean existsByOrderId(String orderId);

        List<Shipment> findByWaveId(String waveId);

        /** Shipments allocated to each node since a point in time (for daily capacity). */
        @Query("select s.nodeId, count(s) from Shipment s where s.nodeId is not null and s.createdAt >= :since " +
               "and s.status <> com.ericguerra.fulfillflow.domain.OrderStatus.CANCELLED group by s.nodeId")
        List<Object[]> loadByNodeSince(@Param("since") java.time.Instant since);
    }

    public interface NodeRepository extends JpaRepository<FulfillmentNode, String> {}

    public interface NodeStockRepository extends JpaRepository<NodeStock, NodeStock.Key> {
        List<NodeStock> findBySkuIn(Collection<String> skus);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select s from NodeStock s where s.sku in :skus order by s.nodeId, s.sku")
        List<NodeStock> lockBySkus(@Param("skus") Collection<String> skus);
    }

    public interface PickWaveRepository extends JpaRepository<PickWave, String> {
        List<PickWave> findByJobExecutionId(Long id);
    }
}
