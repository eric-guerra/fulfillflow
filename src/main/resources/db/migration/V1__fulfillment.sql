CREATE TABLE nodes (
    id              VARCHAR(10) PRIMARY KEY,
    type            VARCHAR(6)  NOT NULL,
    lat             DOUBLE      NOT NULL,
    lng             DOUBLE      NOT NULL,
    daily_capacity  INT         NOT NULL,
    cutoff_hour     INT         NOT NULL
);

CREATE TABLE node_stock (
    node_id    VARCHAR(10) NOT NULL,
    sku        VARCHAR(40) NOT NULL,
    available  INT         NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (node_id, sku),
    CONSTRAINT fk_stock_node FOREIGN KEY (node_id) REFERENCES nodes (id),
    CONSTRAINT ck_stock_nonneg CHECK (available >= 0)
);
CREATE INDEX idx_node_stock_sku ON node_stock (sku);

CREATE TABLE shipments (
    id               VARCHAR(36) PRIMARY KEY,
    order_id         VARCHAR(40) NOT NULL,
    node_id          VARCHAR(10),
    status           VARCHAR(12) NOT NULL,
    ship_method      VARCHAR(12) NOT NULL,
    dest_lat         DOUBLE      NOT NULL,
    dest_lng         DOUBLE      NOT NULL,
    dest_zip         VARCHAR(10) NOT NULL,
    wave_id          VARCHAR(36),
    tracking_number  VARCHAR(40),
    promised_by      TIMESTAMP   NOT NULL,
    created_at       TIMESTAMP   NOT NULL,
    version          BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_shipments_order ON shipments (order_id);
CREATE INDEX idx_shipments_status ON shipments (status, promised_by);

CREATE TABLE shipment_lines (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_id  VARCHAR(36) NOT NULL,
    sku          VARCHAR(40) NOT NULL,
    qty          INT         NOT NULL,
    picked_qty   INT         NOT NULL DEFAULT 0,
    CONSTRAINT fk_line_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id)
);

CREATE TABLE shipment_status_events (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_id  VARCHAR(36)  NOT NULL,
    from_status  VARCHAR(12)  NOT NULL,
    to_status    VARCHAR(12)  NOT NULL,
    reason       VARCHAR(200),
    at           TIMESTAMP    NOT NULL,
    CONSTRAINT fk_event_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id)
);

CREATE TABLE pick_waves (
    id                VARCHAR(36) PRIMARY KEY,
    node_id           VARCHAR(10) NOT NULL,
    ship_method       VARCHAR(12) NOT NULL,
    shipment_count    INT         NOT NULL,
    unit_count        INT         NOT NULL,
    pick_list         TEXT        NOT NULL,
    job_execution_id  BIGINT,
    created_at        TIMESTAMP   NOT NULL
);

INSERT INTO nodes (id, type, lat, lng, daily_capacity, cutoff_hour) VALUES
    ('DC-KTY', 'DC', 29.7858, -95.8245, 5000, 17),
    ('DC-DAL', 'DC', 32.8998, -97.0403, 8000, 18),
    ('ST-HOU1', 'STORE', 29.7390, -95.4637, 40, 15),
    ('ST-AUS1', 'STORE', 30.4021, -97.7253, 40, 15);
