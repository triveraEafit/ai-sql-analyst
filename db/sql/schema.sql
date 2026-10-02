-- AI SQL Analyst — database schema (DDL)
-- Target: PostgreSQL. Lives in the default `public` schema (the application schema
-- the Sql_Validator treats unqualified names as resolving to). _(Requirements 2.1, 2.2)_
--
-- Idempotency approach: this file drops the application tables first (DROP TABLE
-- IF EXISTS ... CASCADE) and recreates them, so re-running schema.sql always yields
-- a clean, known structure. seed.sql assumes the tables are empty after this runs.
--
-- NOTE: This file contains NO secrets. The Read_Only_Role and its GRANTs are created
-- by the 01-init.sh wrapper (task 2.2), not here.

-- Drop in dependency order (CASCADE handles FKs defensively).
DROP TABLE IF EXISTS order_items CASCADE;
DROP TABLE IF EXISTS orders      CASCADE;
DROP TABLE IF EXISTS products    CASCADE;
DROP TABLE IF EXISTS customers   CASCADE;
DROP TABLE IF EXISTS interactions CASCADE;

-- ---------------------------------------------------------------------------
-- Allowlisted e-commerce tables: customers, products, orders, order_items.
-- These four are the ONLY tables the Read_Only_Role will be granted SELECT on.
-- ---------------------------------------------------------------------------

-- Customers who place orders.
CREATE TABLE customers (
    id          BIGSERIAL     PRIMARY KEY,
    name        TEXT          NOT NULL,
    email       TEXT          NOT NULL UNIQUE,
    country     TEXT          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Products available for purchase.
CREATE TABLE products (
    id          BIGSERIAL     PRIMARY KEY,
    name        TEXT          NOT NULL,
    category    TEXT          NOT NULL,
    price       NUMERIC(10,2) NOT NULL CHECK (price >= 0),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Orders placed by customers.
CREATE TABLE orders (
    id           BIGSERIAL    PRIMARY KEY,
    customer_id  BIGINT       NOT NULL REFERENCES customers(id),
    status       VARCHAR(16)  NOT NULL,          -- PENDING | PAID | SHIPPED | CANCELLED
    order_date   DATE         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_order_date  ON orders(order_date);

-- Line items belonging to orders.
CREATE TABLE order_items (
    id          BIGSERIAL     PRIMARY KEY,
    order_id    BIGINT        NOT NULL REFERENCES orders(id),
    product_id  BIGINT        NOT NULL REFERENCES products(id),
    quantity    INTEGER       NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(10,2) NOT NULL CHECK (unit_price >= 0)   -- price captured at order time
);

CREATE INDEX idx_order_items_order_id   ON order_items(order_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);

-- ---------------------------------------------------------------------------
-- Interactions_Table (Change 10). Populated at runtime by the backend through
-- the Read_Write_Datasource; NOT seeded here. The Read_Only_Role gets no
-- privilege on this table (granted/withheld in task 2.2).
-- ---------------------------------------------------------------------------
CREATE TABLE interactions (
    id             BIGSERIAL   PRIMARY KEY,
    question       TEXT        NOT NULL,
    generated_sql  TEXT        NULL,          -- nullable: null when no SQL was generated
    result_summary TEXT        NULL,
    explanation    TEXT        NULL,
    status         VARCHAR(16) NOT NULL,      -- SUCCESS | FAILED
    latency_ms     BIGINT      NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
