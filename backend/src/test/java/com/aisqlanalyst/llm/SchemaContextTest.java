package com.aisqlanalyst.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SchemaContext} (Requirement 1.4). Pure in-process assertions on the
 * rendered prompt text; no Spring context and no database.
 */
class SchemaContextTest {

    private final SchemaContext schemaContext = new SchemaContext();

    @Test
    void includesAllFourAllowlistedTables() {
        String text = schemaContext.asPromptText();

        assertThat(text)
                .contains("customers")
                .contains("products")
                .contains("orders")
                .contains("order_items");
    }

    @Test
    void includesRepresentativeColumns() {
        String text = schemaContext.asPromptText();

        assertThat(text)
                .contains("email")
                .contains("country")
                .contains("category")
                .contains("order_date")
                .contains("unit_price")
                .contains("quantity");
    }

    @Test
    void neverExposesTheInteractionsTable() {
        String text = schemaContext.asPromptText();

        assertThat(text).doesNotContain("interactions");
    }

    @Test
    void statesSelectOnlyAndTableRestriction() {
        String text = schemaContext.asPromptText();

        assertThat(text).containsIgnoringCase("SELECT");
        assertThat(text).containsIgnoringCase("Only these tables");
    }

    @Test
    void rendersEachTableAsCompactColumnListing() {
        String text = schemaContext.asPromptText();

        assertThat(text)
                .contains("customers(id, name, email, country, created_at)")
                .contains("order_items(id, order_id, product_id, quantity, unit_price)");
    }
}
