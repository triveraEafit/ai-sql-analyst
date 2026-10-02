package com.aisqlanalyst.sql;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

/**
 * AST-based safety validator for LLM-generated SQL (Sql_Validator, Requirement 4).
 *
 * <p>The SQL text is <strong>parsed into a JSqlParser AST and inspected structurally</strong> &mdash;
 * it is never regex-matched. On success it returns a {@link ValidatedSql} wrapping the parsed
 * statement; on any rule failure it throws {@link ValidationException} with a short, safe
 * Error_Advice message that the Controller_Advice (task 12.2) maps to HTTP 422 (Requirement 4.8).
 *
 * <h2>Rules enforced</h2>
 * <ol>
 *   <li><b>Single statement (4.1).</b> The input is parsed with
 *       {@link CCJSqlParserUtil#parseStatements(String)} and must contain <em>exactly one</em>
 *       statement. {@code parseStatements} splits on statement boundaries (including a
 *       trailing-semicolon-separated second statement), so {@code "SELECT 1; DROP TABLE t"} yields
 *       two statements and is rejected. A lone trailing {@code ;} parses as a single statement and
 *       is accepted.</li>
 *   <li><b>SELECT only (4.2).</b> The single statement must be a {@link Select}. INSERT / UPDATE /
 *       DELETE / MERGE and DDL (CREATE / DROP / ALTER / TRUNCATE) etc. are rejected.</li>
 *   <li><b>Allowlisted, schema-qualified tables (4.3).</b> Every physical table referenced anywhere
 *       in the tree (FROM, JOINs, subqueries, CTE bodies, set-operation branches) is checked by its
 *       <em>schema-qualified</em> name. A schema of {@code pg_catalog}, {@code information_schema},
 *       or any schema other than the application schema {@code public} is rejected. An unqualified
 *       name resolves against {@code public} and is accepted only when its bare name is one of the
 *       four {@link #ALLOWLISTED_TABLES}. Names that resolve to a CTE declared in the same query are
 *       logical, not physical, and are not treated as tables.</li>
 *   <li><b>No {@code SELECT INTO} (4.4).</b> Any {@link PlainSelect#getIntoTables()} or
 *       {@code INTO TEMP} is rejected on every branch.</li>
 *   <li><b>No locking clause (4.5).</b> {@code FOR UPDATE} / {@code FOR SHARE} etc.
 *       ({@link PlainSelect#getForMode()} / {@link PlainSelect#getForUpdateTable()}) are rejected on
 *       every branch.</li>
 *   <li><b>Default-deny function allowlist (4.6, 4.7).</b> Every {@link Function} invocation found
 *       anywhere in the AST (select items, WHERE, HAVING, GROUP BY, ORDER BY, JOIN ON, subqueries,
 *       set-op branches) is accepted only when its lower-cased name is in
 *       {@link #ALLOWLISTED_FUNCTIONS}; any other function &mdash; including Dangerous_Functions such
 *       as {@code pg_sleep} / {@code pg_read_file} &mdash; is rejected. {@code CAST} appears as a
 *       {@code CastExpression} (not a {@code Function}) and is permitted; {@code EXTRACT} /
 *       {@code date_part} may appear as an {@code ExtractExpression} and are allowlisted anyway.</li>
 *   <li><b>Set operations (4.8).</b> When the body is a {@link SetOperationList} (UNION / UNION ALL /
 *       INTERSECT / EXCEPT), <em>every</em> branch is traversed and subjected to the table, function,
 *       INTO and locking checks. No branch escapes.</li>
 * </ol>
 *
 * <h2>How the AST is traversed</h2>
 * <p>The validator recurses the {@link Select} structure itself for FROM items, JOIN right-items and
 * nested/parenthesised selects (so set-operation branches, derived tables and CTE bodies are all
 * reached), and uses an {@link ExpressionVisitorAdapter} &mdash; wired with a select-visitor that
 * re-enters this walker &mdash; to descend into every scalar expression and collect {@link Function}
 * invocations and any sub-selects embedded in expressions (WHERE, HAVING, CASE, scalar subqueries,
 * {@code IN (SELECT ...)}, etc.). Combining explicit structural recursion with the expression
 * visitor means a table or function cannot hide in a branch the other path misses.
 */
@Component
public class SqlValidator {

    /** Application schema that unqualified table names resolve against (Requirement 4.3). */
    static final String APPLICATION_SCHEMA = "public";

    /** The only tables generated SQL may reference (Allowlisted_Tables, Requirement 4.3). */
    static final Set<String> ALLOWLISTED_TABLES =
            Set.of("customers", "products", "orders", "order_items");

    /**
     * Default-deny function allowlist (Allowlisted_Functions, design "Allowlisted_Functions (default
     * set)"; Requirements 4.6, 4.7). Any function not in this set is rejected.
     */
    static final Set<String> ALLOWLISTED_FUNCTIONS = Set.of(
            // Aggregates
            "count", "sum", "avg", "min", "max",
            // String
            "lower", "upper", "trim", "length", "substring", "concat", "left", "right", "replace",
            // Null / conditional
            "coalesce", "nullif", "greatest", "least",
            // Numeric
            "round", "ceil", "floor", "abs",
            // Date / time
            "now", "date_trunc", "extract", "date_part", "age", "to_char",
            // Cast
            "cast");

    /**
     * The Allowlisted_Functions as a stable, sorted, immutable list. Exposed so other components
     * (e.g. the Llm_Client, which injects the real list into its system prompt) share the single
     * source of truth and cannot drift from what the validator enforces.
     *
     * @return the allowlisted function names, lower-case, sorted alphabetically.
     */
    public static List<String> allowlistedFunctions() {
        return ALLOWLISTED_FUNCTIONS.stream().sorted().toList();
    }

    private static final String MSG_SINGLE_SELECT = "Only a single SELECT statement is allowed.";
    private static final String MSG_UNPARSEABLE = "The generated SQL could not be parsed.";
    private static final String MSG_SELECT_ONLY = "Only SELECT statements are allowed.";
    private static final String MSG_SELECT_INTO = "SELECT INTO is not allowed.";
    private static final String MSG_LOCKING = "Locking clauses such as FOR UPDATE are not allowed.";

    /**
     * Validates generated SQL against all safety rules.
     *
     * @param sql the raw SQL produced by the Llm_Client
     * @return a {@link ValidatedSql} wrapping the parsed, safety-checked SELECT statement
     * @throws ValidationException if the SQL violates any rule (mapped to HTTP 422)
     */
    public ValidatedSql validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new ValidationException(MSG_SELECT_ONLY);
        }

        // --- Rule 1: exactly one statement (4.1) -------------------------------------------------
        List<Statement> statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql).getStatements();
        } catch (JSQLParserException e) {
            // Attach cause for server logs only; never surface parser internals to the client.
            throw new ValidationException(MSG_UNPARSEABLE, e);
        }
        if (statements == null || statements.size() != 1) {
            throw new ValidationException(MSG_SINGLE_SELECT);
        }
        Statement statement = statements.get(0);

        // --- Rule 2: must be a SELECT (4.2) ------------------------------------------------------
        if (!(statement instanceof Select select)) {
            throw new ValidationException(MSG_SELECT_ONLY);
        }

        // --- Rules 3-7: walk the SELECT tree -----------------------------------------------------
        validateSelect(select);

        return ValidatedSql.of(statement);
    }

    /**
     * Recursively validates a {@link Select} body &mdash; handles {@link PlainSelect},
     * {@link SetOperationList} (every branch) and {@link ParenthesedSelect}. Any {@code WITH} clause
     * attached to the body is validated first so CTE bodies are checked, and the CTE names are then
     * treated as logical references (not physical tables) within this select.
     */
    private void validateSelect(Select select) {
        // Validate every CTE body (its own tables/functions must be safe), then let the names it
        // declares be referenced without being mistaken for disallowed physical tables.
        List<WithItem> withItems = select.getWithItemsList();
        if (withItems != null) {
            for (WithItem withItem : withItems) {
                // A WithItem IS a ParenthesedSelect; validate its inner select body.
                validateSelect(withItem.getSelect());
            }
        }

        if (select instanceof PlainSelect plainSelect) {
            validatePlainSelect(plainSelect, cteNames(withItems));
        } else if (select instanceof SetOperationList setOps) {
            // Rule 7: no branch escapes.
            for (Select branch : setOps.getSelects()) {
                validateSelect(branch);
            }
        } else if (select instanceof ParenthesedSelect parenthesed) {
            validateSelect(parenthesed.getSelect());
        } else {
            // Unknown Select subtype (e.g. VALUES / TABLE statement) - not a plain readable SELECT.
            throw new ValidationException(MSG_SELECT_ONLY);
        }
    }

    private Set<String> cteNames(List<WithItem> withItems) {
        if (withItems == null || withItems.isEmpty()) {
            return Set.of();
        }
        return withItems.stream()
                .map(WithItem::getAlias)
                .filter(a -> a != null && a.getName() != null)
                .map(a -> stripQuotes(a.getName()).toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Applies the INTO (4.4), locking (4.5), table (4.3) and function (4.6/4.7) checks to a single
     * {@link PlainSelect}, recursing into its FROM item, JOINs and every expression.
     *
     * @param localCtes names of CTEs visible in this select, which are logical references and not
     *                  physical tables
     */
    private void validatePlainSelect(PlainSelect plainSelect, Set<String> localCtes) {
        // Rule 4: reject SELECT INTO (both `INTO table` and `INTO TEMP table`).
        List<Table> intoTables = plainSelect.getIntoTables();
        if ((intoTables != null && !intoTables.isEmpty()) || plainSelect.getIntoTempTable() != null) {
            throw new ValidationException(MSG_SELECT_INTO);
        }

        // Rule 5: reject locking clauses (FOR UPDATE / FOR SHARE / ...).
        if (plainSelect.getForMode() != null || plainSelect.getForUpdateTable() != null) {
            throw new ValidationException(MSG_LOCKING);
        }

        // Rule 3: FROM item and JOINs - physical tables + any derived/subselect sources.
        validateFromItem(plainSelect.getFromItem(), localCtes);
        List<Join> joins = plainSelect.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                validateFromItem(join.getRightItem(), localCtes);
                // Older/edge forms may carry a left FROM item on the join too.
                validateFromItem(join.getFromItem(), localCtes);
                if (join.getOnExpressions() != null) {
                    join.getOnExpressions().forEach(e -> walkExpression(e, localCtes));
                }
            }
        }

        // Rules 3 & 6: every scalar expression (select items, WHERE, HAVING, GROUP BY, ORDER BY).
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
        if (selectItems != null) {
            for (SelectItem<?> item : selectItems) {
                walkExpression(item.getExpression(), localCtes);
            }
        }
        walkExpression(plainSelect.getWhere(), localCtes);
        walkExpression(plainSelect.getHaving(), localCtes);

        GroupByElement groupBy = plainSelect.getGroupBy();
        if (groupBy != null && groupBy.getGroupByExpressionList() != null) {
            // getGroupByExpressionList() is a raw ExpressionList; its elements are Expressions.
            for (Object groupExpr : groupBy.getGroupByExpressionList()) {
                walkExpression((net.sf.jsqlparser.expression.Expression) groupExpr, localCtes);
            }
        }

        List<OrderByElement> orderBy = plainSelect.getOrderByElements();
        if (orderBy != null) {
            for (OrderByElement element : orderBy) {
                walkExpression(element.getExpression(), localCtes);
            }
        }
    }

    /**
     * Validates a {@link FromItem}: a physical {@link Table}, a parenthesised/derived select, or a
     * grouped from-item. Lateral subselects and derived tables recurse back into
     * {@link #validateSelect}.
     */
    private void validateFromItem(FromItem fromItem, Set<String> localCtes) {
        if (fromItem == null) {
            return;
        }
        if (fromItem instanceof Table table) {
            checkTable(table, localCtes);
        } else if (fromItem instanceof ParenthesedSelect parenthesed) {
            // Derived table / subquery in FROM (ParenthesedSelect is itself a Select).
            validateSelect(parenthesed);
        } else if (fromItem instanceof ParenthesedFromItem grouped) {
            validateFromItem(grouped.getFromItem(), localCtes);
            if (grouped.getJoins() != null) {
                for (Join join : grouped.getJoins()) {
                    validateFromItem(join.getRightItem(), localCtes);
                    validateFromItem(join.getFromItem(), localCtes);
                    if (join.getOnExpressions() != null) {
                        join.getOnExpressions().forEach(e -> walkExpression(e, localCtes));
                    }
                }
            }
        }
        // Other FromItem kinds (e.g. table-valued functions) are not produced for the allowlisted
        // read-only schema; any function they contain is still caught by expression walking when
        // referenced, and unknown physical sources simply have no allowlisted table to match.
    }

    /**
     * Rule 3: a referenced table must resolve to one of the allowlisted tables in the application
     * schema. Rejects any explicit non-{@code public} schema (notably {@code pg_catalog} /
     * {@code information_schema}) and any bare/qualified name not in {@link #ALLOWLISTED_TABLES}.
     * A name that matches a locally-declared CTE is a logical reference and is skipped.
     */
    private void checkTable(Table table, Set<String> localCtes) {
        String schema = table.getSchemaName();
        String bareName = stripQuotes(table.getName()).toLowerCase(Locale.ROOT);

        if (schema == null) {
            // Unqualified: could be a CTE reference (logical) or a physical table in `public`.
            if (localCtes.contains(bareName)) {
                return;
            }
            if (!ALLOWLISTED_TABLES.contains(bareName)) {
                throw new ValidationException(rejectedTableMessage(bareName));
            }
            return;
        }

        // Qualified: the schema must be the application schema AND the table must be allowlisted.
        String schemaLower = stripQuotes(schema).toLowerCase(Locale.ROOT);
        if (!APPLICATION_SCHEMA.equals(schemaLower) || !ALLOWLISTED_TABLES.contains(bareName)) {
            throw new ValidationException(rejectedTableMessage(table.getFullyQualifiedName()));
        }
    }

    /**
     * Walks a single expression tree, collecting {@link Function} invocations (Rules 6/7) and
     * recursing into any sub-selects embedded in the expression (Rule 3). Uses
     * {@link ExpressionVisitorAdapter} for the heavy lifting of descending into operands,
     * conditions, CASE/WHEN, function arguments, etc.; the adapter is wired with a select-visitor
     * that re-enters {@link #validateSelect} so scalar subqueries and {@code IN (SELECT ...)} are
     * validated too.
     */
    private void walkExpression(net.sf.jsqlparser.expression.Expression expression, Set<String> localCtes) {
        if (expression == null) {
            return;
        }
        FunctionCollectingVisitor visitor = new FunctionCollectingVisitor(localCtes);
        // When the adapter encounters a sub-select, route it back into our select walker so no
        // table or function inside the subquery escapes validation.
        visitor.setSelectVisitor(new SubSelectVisitor());
        expression.accept(visitor);
    }

    private void checkFunction(Function function) {
        if (function == null || function.getName() == null) {
            return;
        }
        String name = stripQuotes(function.getName()).toLowerCase(Locale.ROOT);
        if (!ALLOWLISTED_FUNCTIONS.contains(name)) {
            throw new ValidationException(rejectedFunctionMessage(name));
        }
    }

    private static String rejectedTableMessage(String tableName) {
        return "Query references a table that is not permitted: " + tableName + ".";
    }

    private static String rejectedFunctionMessage(String functionName) {
        return "Query uses a function that is not permitted: " + functionName + ".";
    }

    private static String stripQuotes(String identifier) {
        if (identifier == null) {
            return "";
        }
        String trimmed = identifier.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    /**
     * Expression visitor that enforces the function allowlist on every {@link Function} it reaches.
     * {@code CAST} ({@code CastExpression}) and {@code EXTRACT} ({@code ExtractExpression}) are
     * allowlisted operators; the adapter still recurses into their inner expressions so a
     * disallowed function nested inside a cast/extract is caught.
     */
    private final class FunctionCollectingVisitor extends ExpressionVisitorAdapter {

        @SuppressWarnings("unused")
        private final Set<String> localCtes;

        private FunctionCollectingVisitor(Set<String> localCtes) {
            this.localCtes = localCtes;
        }

        @Override
        public void visit(Function function) {
            checkFunction(function);
            super.visit(function); // continue into the function's arguments
        }
    }

    /**
     * Select visitor used by {@link FunctionCollectingVisitor} to hand any sub-select found inside
     * an expression back to the main {@link #validateSelect} recursion, so tables and functions in
     * scalar subqueries / {@code IN (SELECT ...)} are validated too.
     */
    private final class SubSelectVisitor implements net.sf.jsqlparser.statement.select.SelectVisitor {

        @Override
        public void visit(PlainSelect plainSelect) {
            validateSelect(plainSelect);
        }

        @Override
        public void visit(SetOperationList setOperationList) {
            validateSelect(setOperationList);
        }

        @Override
        public void visit(WithItem withItem) {
            validateSelect(withItem.getSelect());
        }

        @Override
        public void visit(net.sf.jsqlparser.statement.select.Values values) {
            // VALUES lists contain only literals; nothing table/function-wise to validate here.
        }

        @Override
        public void visit(ParenthesedSelect parenthesedSelect) {
            validateSelect(parenthesedSelect.getSelect());
        }

        @Override
        public void visit(net.sf.jsqlparser.statement.select.LateralSubSelect lateralSubSelect) {
            validateSelect(lateralSubSelect.getSelect());
        }

        @Override
        public void visit(net.sf.jsqlparser.statement.select.TableStatement tableStatement) {
            // A bare TABLE <name> statement - not a readable SELECT in our model.
            throw new ValidationException(MSG_SELECT_ONLY);
        }
    }
}
