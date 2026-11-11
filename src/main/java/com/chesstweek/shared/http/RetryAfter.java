package com.chesstweek.shared.http;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class RetryAfter {

    private RetryAfter() {}

    public static Long parse(String value, Instant now) {
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds >= 1 ? seconds : null;
        } catch (NumberFormatException exception) {
            try {
                Instant retryAt = ZonedDateTime.parse(
                                value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                Duration remaining = Duration.between(now, retryAt);
                if (remaining.isNegative() || remaining.isZero()) {
                    return null;
                }
                long seconds = remaining.getSeconds();
                return remaining.getNano() == 0 ? seconds : Math.addExact(seconds, 1);
            } catch (ArithmeticException | DateTimeParseException invalidDate) {
                return null;
            }
        }
    }
}
