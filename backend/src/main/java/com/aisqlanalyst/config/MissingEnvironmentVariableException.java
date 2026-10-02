package com.aisqlanalyst.config;

import java.util.List;

/**
 * Thrown during startup when one or more Required_Environment_Variables are absent or blank
 * (Requirement 11.1).
 *
 * <p>Carries the names of the missing variables so callers and tests can assert on them. The
 * message and this type never carry any variable <em>value</em>, keeping secrets out of logs and
 * stack traces (Requirement 11.2, 11.3).
 */
public class MissingEnvironmentVariableException extends RuntimeException {

    private final transient List<String> missingVariables;

    public MissingEnvironmentVariableException(String message, List<String> missingVariables) {
        super(message);
        this.missingVariables = List.copyOf(missingVariables);
    }

    /** @return the names (never values) of the missing required environment variables. */
    public List<String> getMissingVariables() {
        return missingVariables;
    }
}
