/**
 * Service layer (Requirement 12.1).
 *
 * <p>Holds the Query_Service that orchestrates the question-to-answer flow:
 * LLM generation, validation, bounded execution, the single retry on
 * correctable errors, and interaction persistence. Kept separate from the
 * controller layer.
 */
package com.aisqlanalyst.service;
