package com.aisqlanalyst.dto;

import java.util.List;
import java.util.Map;

/**
 * Success body for {@code POST /api/query} (Requirement 1.1).
 *
 * <p>Returned when a generated query validates and executes successfully. The table rows are
 * carried as a list of column-name -&gt; value maps so arbitrary SELECT result shapes can be
 * serialized to JSON without a fixed schema.
 *
 * @param table       the result rows, each a map of column name to value (bounded by Enforced_Limit).
 * @param sql         the generated SQL that produced the result, surfaced to the user.
 * @param explanation the natural-language explanation of the query/result.
 */
public record QueryResponse(

        List<Map<String, Object>> table,

        String sql,

        String explanation
) {
}
