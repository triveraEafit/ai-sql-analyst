package com.aisqlanalyst.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for {@link QuestionLengthValidator} and {@link QuestionTooLongException}
 * (Requirements 1.2, 10.2; Design Change 6).
 *
 * <p>Pure unit tests: the validator is constructed directly with a small maximum, so there is no
 * Spring context, no database, and no Docker involved.
 */
class QuestionLengthValidatorTest {

    private static final int MAX = 10;

    private final QuestionLengthValidator validator = new QuestionLengthValidator(MAX);

    @Test
    void acceptsQuestionExactlyAtTheLimit() {
        String atLimit = "a".repeat(MAX);

        assertThatCode(() -> validator.enforceMaxLength(atLimit)).doesNotThrowAnyException();
    }

    @Test
    void rejectsQuestionOneCharacterOverTheLimit() {
        String overLimit = "a".repeat(MAX + 1);

        assertThatThrownBy(() -> validator.enforceMaxLength(overLimit))
                .isInstanceOf(QuestionTooLongException.class)
                .hasMessageContaining(Integer.toString(MAX));
    }

    @Test
    void acceptsShortQuestion() {
        assertThatCode(() -> validator.enforceMaxLength("hi")).doesNotThrowAnyException();
    }

    @Test
    void exceptionCarriesConfiguredMaximum() {
        QuestionTooLongException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                QuestionTooLongException.class,
                () -> validator.enforceMaxLength("a".repeat(MAX + 5)));

        assertThat(thrown.getMaxQuestionLength()).isEqualTo(MAX);
    }
}
