# FulfillFlow

An order-fulfillment orchestrator: it takes placed orders from **RabbitMQ**, decides which distribution center or store ships each item, releases **pick waves** to the warehouse floor with **Spring Batch**, tracks every shipment through pick, pack and ship, and exports carrier manifests. Built on **MySQL**.

> Portfolio reference implementation of a 2016 retail-fulfillment project (Java, Spring Batch, RabbitMQ, MySQL), built on Spring Boot 3 / Spring Batch 5.

## Flow

```
orders.placed ──▶ allocate (lock stock rows) ──▶ shipments ALLOCATED / BACKORDERED
                                                        │  every 15 min: waveJob
                                                        ▼
                                  WAVED ──pick──▶ PICKED ──pack──▶ PACKED ──ship──▶ SHIPPED ──▶ manifestJob CSV
                                    └── short pick ──▶ back to ALLOCATED
fulfillment.status (topic, routing key shipment.<STATUS>) ◀── every transition, after commit
```

## Allocation ([AllocationEngine](src/main/java/com/ericguerra/fulfillflow/allocation/AllocationEngine.java))

A pure function, unit-tested against real Texas node locations:
1. **Ship complete from one node** if possible, preferring DCs over stores (store labor costs more), then the closest node
2. Otherwise **split greedily**: take the node that covers the most remaining units, repeat
3. Backorder the rest; `retryBackorders()` releases a backorder only when a single node can ship it complete
4. Nodes at today's capacity are skipped (stores have small daily limits)

Stock rows for the order's SKUs are row-locked in a consistent order during allocation, so concurrent orders can't double-allocate. A CHECK constraint backs this up. Intake is idempotent on `orderId`.

## Batch jobs

- **waveJob** (tasklet, one transaction): groups ALLOCATED shipments by node and ship method, fastest service first and earliest promise first, capped per pick cart (shipments and units). It builds a **pick-then-sort** list aggregated per SKU in bin order
- **manifestJob** (chunk-oriented, size 100): JPA paging reader → processor that filters unlabeled shipments → `FlatFileItemWriter` CSV. It's restartable from the last committed chunk

Every status change is recorded in an audit trail (`shipment_status_events`), enforced by an explicit state machine (`OrderStatus.canMoveTo`).

## Run and test

```bash
docker compose up --build
curl -XPOST localhost:8080/api/orders -H 'content-type: application/json' \
  -d '{"orderId":"O-1","lines":{"TENT-2P":1},"shipMethod":"GROUND","destLat":29.76,"destLng":-95.37,"destZip":"77002"}'
curl -XPOST localhost:8080/api/jobs/waves

mvn test    # allocation and wave-planning unit tests; H2 integration running the real batch jobs
```
