package com.aisqlanalyst.service;

/**
 * Thrown when a submitted Question exceeds the configurable {@code Max_Question_Length}
 * (Requirements 1.2, 10.2).
 *
 * <p>This is an unchecked {@link RuntimeException} raised by the manual length check
 * (see Design Change 6). Bean Validation {@code @Size(max=...)} requires a compile-time
 * constant and cannot read the runtime-configurable {@code app.max-question-length}, so the
 * length check is performed manually against the injected {@code Max_Question_Length} and this
 * exception is thrown when the limit is exceeded. The global {@code Controller_Advice}
 * (task 12.2) maps it to HTTP 400 with a safe Error_Advice message that states the maximum
 * allowed length.
 *
 * <p>The configured maximum is carried on the exception via {@link #getMaxQuestionLength()} so
 * the advice can render the limit without re-reading configuration. The {@linkplain
 * #getMessage() message} is self-contained and safe to surface to clients: it mentions only the
 * numeric limit and never echoes the (potentially large) offending Question.
 *
 * <p>Placed in {@code com.aisqlanalyst.service} because the Query_Service orchestration
 * (task 10) performs the check at the top of the flow; it is equally reusable from the web
 * layer (task 12).
 */
public class QuestionTooLongException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int maxQuestionLength;

    /**
     * Creates the exception for a Question that exceeded the limit.
     *
     * @param maxQuestionLength the configured {@code Max_Question_Length} that was exceeded;
     *                          rendered into the message and available to the advice.
     */
    public QuestionTooLongException(int maxQuestionLength) {
        super("Question exceeds the maximum length of " + maxQuestionLength + " characters.");
        this.maxQuestionLength = maxQuestionLength;
    }

    /**
     * The configured {@code Max_Question_Length} that was exceeded.
     *
     * @return the maximum permitted Question length in characters.
     */
    public int getMaxQuestionLength() {
        return maxQuestionLength;
    }
}
