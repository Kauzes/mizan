package dev.kauzes.mizan.common.error;

import java.util.Objects;

/** One rejected field, in the shape a form can render next to the input. */
public record FieldViolation(String field, String message) {

    public FieldViolation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }
}
