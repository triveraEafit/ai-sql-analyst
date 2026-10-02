package com.aisqlanalyst.dto;

/**
 * Safe error body (Error_Advice) returned by the Global Controller_Advice (task 12.2).
 *
 * <p>Deliberately minimal: it carries only a short machine-readable error code and a
 * human-readable message. It MUST NEVER carry secrets, credentials, SQLState internals, or
 * stack traces — error details that could leak sensitive information stay server-side in logs
 * (Requirements 11.2, 11.3).
 *
 * @param error   a short, stable, machine-readable error code (e.g. {@code "bad_request"}).
 * @param message a safe, human-readable description with no sensitive details.
 */
public record ErrorResponse(

        String error,

        String message
) {
}
