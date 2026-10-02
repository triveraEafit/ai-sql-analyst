package com.aisqlanalyst.llm;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Static, LLM-friendly description of the Allowlisted_Tables and their columns, assembled once at
 * startup and included in every LLM generation request (Schema_Context, Requirement 1.4).
 *
 * <h2>Single source of truth</h2>
 * <p>The table and column definitions below are a <strong>hand-maintained mirror of
 * {@code db/sql/schema.sql}</strong> (the DDL that the init/seed scripts apply to the real
 * database). They are kept as Java constants rather than parsed from the SQL file at runtime: the
 * schema is small and static, the backend never needs database access just to build its prompt
 * context, and a well-commented mirror is simpler and more robust than a runtime DDL parser.
 *
 * <p><strong>These definitions MUST be kept in sync with {@code db/sql/schema.sql}.</strong> If a
 * column is added, removed, or renamed there, update {@link #ALLOWLISTED_TABLES} here too; otherwise
 * the context handed to the LLM would drift from the actual schema. The <em>set</em> of tables must
 * also stay aligned with the allowlist enforced by
 * {@code com.aisqlanalyst.sql.SqlValidator#ALLOWLISTED_TABLES}
 * (customers, products, orders, order_items). The {@code interactions} table is deliberately
 * <strong>excluded</strong>: it is not allowlisted and the read-only role cannot see it, so the LLM
 * must never be told it exists.
 */
@Component
public class SchemaContext {

    /**
     * The Allowlisted_Tables and their columns, mirroring {@code db/sql/schema.sql} exactly
     * (same order as the DDL). Keep this list in sync with that file and with
     * {@code SqlValidator#ALLOWLISTED_TABLES}. The {@code interactions} table is intentionally
     * omitted (not allowlisted, invisible to the read-only role).
     */
    static final List<Table> ALLOWLISTED_TABLES = List.of(
            new Table("customers", List.of("id", "name", "email", "country", "created_at")),
            new Table("products", List.of("id", "name", "category", "price", "created_at")),
            new Table("orders", List.of("id", "customer_id", "status", "order_date", "created_at")),
            new Table("order_items", List.of("id", "order_id", "product_id", "quantity", "unit_price")));

    /** Rendered once at construction; the context is static for the life of the application. */
    private final String promptText;

    public SchemaContext() {
        this.promptText = render();
    }

    /**
     * Returns the Schema_Context as a concise, deterministic block of text suitable for inclusion
     * in an LLM prompt. The format is a {@code table(col, col, ...)} listing (one table per line)
     * followed by a one-line usage note constraining the model to these tables and to SELECT-only
     * queries.
     *
     * @return the Schema_Context prompt text.
     */
    public String asPromptText() {
        return promptText;
    }

    private static String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Database schema (PostgreSQL). Only the following tables and columns exist:\n");
        for (Table table : ALLOWLISTED_TABLES) {
            sb.append("- ")
              .append(table.name())
              .append('(')
              .append(String.join(", ", table.columns()))
              .append(")\n");
        }
        sb.append("Only these tables may be queried, and only read-only SELECT statements are allowed.");
        return sb.toString();
    }

    /**
     * One allowlisted table and its ordered column names. Package-private immutable holder used to
     * mirror the DDL; see {@link #ALLOWLISTED_TABLES}.
     *
     * @param name    the table name as it appears in {@code schema.sql}.
     * @param columns the table's column names, in DDL order.
     */
    record Table(String name, List<String> columns) {
    }
}
