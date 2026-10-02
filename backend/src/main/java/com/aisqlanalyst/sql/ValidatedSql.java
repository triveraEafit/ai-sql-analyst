package com.aisqlanalyst.sql;

import net.sf.jsqlparser.statement.Statement;

/**
 * The successful result of {@link SqlValidator#validate(String)}: a single, parsed, safety-checked
 * read-only {@code SELECT} statement.
 *
 * <p>Carries the JSqlParser {@link Statement} (always a
 * {@link net.sf.jsqlparser.statement.select.Select}) and its normalized text. The
 * {@code normalizedSql} is produced from {@link Statement#toString()} on the parsed AST, so it is
 * the canonical rendering of the statement the validator actually inspected &mdash; not the raw LLM
 * string.
 *
 * <p><strong>No trailing semicolon.</strong> JSqlParser's {@code Statement.toString()} never emits a
 * trailing {@code ;}. The Sql_Executor (task 7.1) relies on this: it wraps {@code normalizedSql} in a
 * {@code SELECT * FROM ( ... ) AS _wrapped LIMIT n} subquery, which would be a syntax error if the
 * inner text carried a semicolon. Running the normalized AST text (rather than the raw string) also
 * removes any stray-token or trailing-semicolon ambiguity before wrapping (design Change 3).
 *
 * @param statement     the parsed, validated SELECT statement
 * @param normalizedSql {@code statement.toString()} &mdash; canonical, semicolon-free SQL text
 */
public record ValidatedSql(Statement statement, String normalizedSql) {

    public ValidatedSql {
        if (statement == null) {
            throw new IllegalArgumentException("statement must not be null");
        }
        if (normalizedSql == null) {
            throw new IllegalArgumentException("normalizedSql must not be null");
        }
    }

    /**
     * Convenience factory that derives the normalized text from the parsed statement, guaranteeing
     * {@code normalizedSql} is exactly {@code statement.toString()}.
     */
    public static ValidatedSql of(Statement statement) {
        return new ValidatedSql(statement, statement.toString());
    }
}
