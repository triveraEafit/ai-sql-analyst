package com.aisqlanalyst.llm;

import java.util.Locale;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic, offline {@link LlmClient} for local development and demos, selected only when
 * {@code LLM_PROVIDER=mock} (Requirement 1.4, design "Llm_Client" Change 11).
 *
 * <h2>Bean selection</h2>
 * <p>The property is bound as {@code app.llm.provider} (fed by the {@code LLM_PROVIDER} environment
 * variable via {@code application.yml}: {@code app.llm.provider: ${LLM_PROVIDER:http}}). This bean
 * is registered <strong>only</strong> when that property equals {@code mock} via
 * {@link ConditionalOnProperty}. The real {@code HttpLlmClient} (task 8.3) is the default and will
 * be wired to activate when the provider is anything other than {@code mock} (it will use
 * {@code matchIfMissing}/an {@code else} condition), so that exactly one {@link LlmClient} bean is
 * active at a time &mdash; this one in mock mode, the HTTP client otherwise.
 *
 * <h2>What it returns</h2>
 * <p>No network calls are made. Instead the mock inspects the Question with simple case-insensitive
 * keyword matching and returns a fixed, hand-written SQL answer for each of the frontend's example
 * questions, plus a safe fallback for anything unrecognised. <strong>Every</strong> SQL string it
 * returns is a single read-only {@code SELECT} that references only the Allowlisted_Tables
 * ({@code customers}, {@code products}, {@code orders}, {@code order_items}) and only allowlisted
 * functions ({@code count}, {@code sum}, {@code date_trunc}), so it always passes the Sql_Validator
 * and executes end-to-end against the seeded database. SQL is emitted without a trailing semicolon.
 *
 * <h2>Retry path</h2>
 * <p>Because the mock is deterministic and its answers are already valid, the retry overload
 * (non-null {@code errorHint}) simply returns the same answer as the initial generation: there is
 * nothing to correct. The hint is accepted and ignored. This is sufficient for local/demo use.
 */
@Component
@ConditionalOnProperty(prefix = "app.llm", name = "provider", havingValue = "mock")
public class MockLlmClient implements LlmClient {

    /**
     * Returns a deterministic {@link LlmResult} for the Question by keyword matching. The
     * {@code schemaContext} and {@code errorHint} are accepted for interface compatibility but not
     * needed: the mock's answers are fixed and already valid, so the retry path yields the same
     * result as the initial call.
     *
     * @param question      the user's natural-language Question (matched case-insensitively).
     * @param schemaContext the Schema_Context text (unused; the mock has fixed answers).
     * @param errorHint     a prior-failure hint (unused; the mock's answers are already valid).
     * @return a fixed, valid {@link LlmResult} for the matched question, or a safe fallback.
     */
    @Override
    public LlmResult generateSql(String question, String schemaContext, String errorHint) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);

        // Demo-only trigger: a question mentioning "drop table" makes the mock emit an unsafe,
        // non-SELECT statement so the Sql_Validator rejection path (HTTP 422) can be exercised
        // end-to-end in mock mode. The validator rejects this; it is never executed.
        if (q.contains("drop table")) {
            return dropTableAttempt();
        }
        if (containsAll(q, "top", "customer") || (q.contains("customer") && q.contains("spend"))) {
            return topCustomersBySpending();
        }
        if (q.contains("revenue") && q.contains("month")) {
            return revenueByMonth();
        }
        if (q.contains("best") && (q.contains("sell") || q.contains("product"))) {
            return bestSellingProducts();
        }
        if (q.contains("order") && q.contains("status")) {
            return ordersByStatus();
        }
        if (q.contains("customer") && q.contains("country")) {
            return customersByCountry();
        }
        return fallback();
    }

    private static boolean containsAll(String haystack, String... needles) {
        for (String needle : needles) {
            if (!haystack.contains(needle)) {
                return false;
            }
        }
        return true;
    }

    /** Top 5 customers by total spend (customers &times; orders &times; order_items). */
    private static LlmResult topCustomersBySpending() {
        String sql = "SELECT c.name, SUM(oi.quantity * oi.unit_price) AS total_spend "
                + "FROM customers c "
                + "JOIN orders o ON o.customer_id = c.id "
                + "JOIN order_items oi ON oi.order_id = o.id "
                + "GROUP BY c.id, c.name "
                + "ORDER BY total_spend DESC "
                + "LIMIT 5";
        return new LlmResult(sql,
                "Lists the five customers with the highest total spend, summing quantity times unit price across their order items.");
    }

    /** Total revenue grouped by calendar month. */
    private static LlmResult revenueByMonth() {
        String sql = "SELECT date_trunc('month', o.order_date) AS month, "
                + "SUM(oi.quantity * oi.unit_price) AS revenue "
                + "FROM orders o "
                + "JOIN order_items oi ON oi.order_id = o.id "
                + "GROUP BY date_trunc('month', o.order_date) "
                + "ORDER BY month";
        return new LlmResult(sql,
                "Shows total revenue for each month, summing quantity times unit price over order items grouped by order month.");
    }

    /** Top 5 products by units sold (products &times; order_items). */
    private static LlmResult bestSellingProducts() {
        String sql = "SELECT p.name, SUM(oi.quantity) AS units_sold "
                + "FROM products p "
                + "JOIN order_items oi ON oi.product_id = p.id "
                + "GROUP BY p.id, p.name "
                + "ORDER BY units_sold DESC "
                + "LIMIT 5";
        return new LlmResult(sql,
                "Ranks the five best-selling products by total units sold across all order items.");
    }

    /** Count of orders grouped by status. */
    private static LlmResult ordersByStatus() {
        String sql = "SELECT status, count(*) AS order_count "
                + "FROM orders "
                + "GROUP BY status "
                + "ORDER BY order_count DESC";
        return new LlmResult(sql,
                "Counts how many orders fall into each order status.");
    }

    /** Count of customers grouped by country. */
    private static LlmResult customersByCountry() {
        String sql = "SELECT country, count(*) AS customer_count "
                + "FROM customers "
                + "GROUP BY country "
                + "ORDER BY customer_count DESC";
        return new LlmResult(sql,
                "Counts how many customers belong to each country, most populous first.");
    }

    /**
     * Demo-only: returns a dangerous non-SELECT statement so the Sql_Validator rejection (422) can
     * be shown end-to-end in mock mode. The validator always rejects this; it never reaches the DB.
     */
    private static LlmResult dropTableAttempt() {
        return new LlmResult("DROP TABLE customers",
                "Attempts to drop a table; the validator rejects this as it is not a read-only SELECT.");
    }

    /** Safe default for an unrecognised question: a simple customer count. */
    private static LlmResult fallback() {
        String sql = "SELECT count(*) AS customer_count FROM customers";
        return new LlmResult(sql,
                "Could not match the question to a known example, so this returns the total number of customers.");
    }
}
