package com.aisqlanalyst.service;

import com.aisqlanalyst.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reusable guard that enforces the configurable {@code Max_Question_Length}
 * (Requirements 1.2, 10.2; Design Change 6).
 *
 * <p>This is the manual length check that replaces a Bean Validation {@code @Size}: {@code @Size}
 * needs a compile-time constant, whereas the limit here is read at runtime from
 * {@link AppProperties#maxQuestionLength()} ({@code app.max-question-length}, default 300) so the
 * value stays configurable.
 *
 * <p>The guard is intentionally minimal: it checks only the length. Empty / whitespace-only
 * Questions are already rejected by {@code @NotBlank} on {@code QueryRequest} (Requirement 1.3),
 * so this component does not re-check blankness. A {@code null} Question is treated as having no
 * length and is accepted here, leaving the {@code @NotBlank} / {@code @NotNull} contract to the
 * request-validation layer.
 *
 * <p>Length is measured with {@link String#length()}, i.e. the number of Java {@code char} values
 * (UTF-16 code units). That is the natural, cheap measure for this requirement; a Question built
 * from supplementary (astral) code points would count each as two units, which is acceptable for a
 * coarse input-size bound and matches the client-side counter semantics.
 *
 * <p>Registered as a {@code @Component} so the Query_Service (task 10) and/or the web layer
 * (task 12) can inject and call it; wiring into the controller/advice is deferred to those tasks.
 */
@Component
public class QuestionLengthValidator {

    private final int maxQuestionLength;

    /**
     * Creates the validator from the bound application properties.
     *
     * <p>Annotated {@code @Autowired} to mark it as the constructor Spring must use for
     * dependency injection: the class also exposes a raw-int constructor for tests, and with two
     * candidate constructors Spring would otherwise be unable to choose one.
     *
     * @param appProperties the configurable tunables supplying {@code Max_Question_Length}.
     */
    @Autowired
    public QuestionLengthValidator(AppProperties appProperties) {
        this(appProperties.maxQuestionLength());
    }

    /**
     * Creates the validator from a raw maximum. Primarily useful for focused unit tests that want
     * to exercise the guard without constructing a full {@link AppProperties}.
     *
     * @param maxQuestionLength the maximum permitted Question length in characters.
     */
    public QuestionLengthValidator(int maxQuestionLength) {
        this.maxQuestionLength = maxQuestionLength;
    }

    /**
     * Enforces that the Question does not exceed {@code Max_Question_Length}.
     *
     * <p>A Question whose length is exactly the limit is accepted; one character over the limit is
     * rejected. A {@code null} Question is accepted (length handling is left to {@code @NotBlank}).
     *
     * @param question the submitted Question text.
     * @throws QuestionTooLongException when {@code question.length() > maxQuestionLength}.
     */
    public void enforceMaxLength(String question) {
        if (question != null && question.length() > maxQuestionLength) {
            throw new QuestionTooLongException(maxQuestionLength);
        }
    }
}
