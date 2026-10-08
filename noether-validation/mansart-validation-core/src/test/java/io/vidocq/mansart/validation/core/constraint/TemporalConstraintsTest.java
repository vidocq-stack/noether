/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.validation.core.constraint;

import static io.vidocq.mansart.validation.core.constraint.TestSupport.CLOCK;
import static io.vidocq.mansart.validation.core.constraint.TestSupport.annotation;
import static io.vidocq.mansart.validation.core.constraint.TestSupport.pattern;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.chrono.HijrahDate;
import java.time.chrono.JapaneseDate;
import java.time.chrono.MinguoDate;
import java.time.chrono.ThaiBuddhistDate;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * Tests for Past, PastOrPresent, Future and FutureOrPresent (Jakarta Validation 3.1, section 6.1) against a fixed
 * clock: 2026-06-15T12:00:00Z, zone Asia/Tokyo (local 2026-06-15T21:00).
 */
class TemporalConstraintsTest {

    static final long MS = CLOCK.millis();
    static final Instant NOW = CLOCK.instant();

    static class F {
        @Past Object past;
        @PastOrPresent Object pastOrPresent;
        @Future Object future;
        @FutureOrPresent Object futureOrPresent;
    }

    /** Mimics java.sql.Date, whose toInstant() throws. */
    private static Date legacyDate(long millis) {
        return new Date(millis) {
            @Override
            public Instant toInstant() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static Calendar calendar(long millis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
        calendar.setTimeInMillis(millis);
        return calendar;
    }

    @Test
    void past() {
        var a = annotation(F.class, "past", Past.class);
        assertThat(pattern(new PastValidatorForDate(), a, new Date(MS - 1), new Date(MS), new Date(MS + 1), null)).as("Date").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForCalendar(), a, calendar(MS - 1), calendar(MS), calendar(MS + 1), null)).as("Calendar").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForInstant(), a, NOW.minusNanos(1), NOW, NOW.plusNanos(1), null)).as("Instant").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForLocalDate(), a, LocalDate.now(CLOCK).minusDays(1), LocalDate.now(CLOCK), LocalDate.now(CLOCK).plusDays(1), null)).as("LocalDate").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForLocalDateTime(), a, LocalDateTime.now(CLOCK).minusNanos(1), LocalDateTime.now(CLOCK), LocalDateTime.now(CLOCK).plusNanos(1), null)).as("LocalDateTime").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForLocalTime(), a, LocalTime.now(CLOCK).minusNanos(1), LocalTime.now(CLOCK), LocalTime.now(CLOCK).plusNanos(1), null)).as("LocalTime").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForMonthDay(), a, MonthDay.of(6, 14), MonthDay.of(6, 15), MonthDay.of(6, 16), null)).as("MonthDay").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForOffsetDateTime(), a, OffsetDateTime.now(CLOCK).minusNanos(1), OffsetDateTime.now(CLOCK), OffsetDateTime.now(CLOCK).plusNanos(1), null)).as("OffsetDateTime").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForOffsetTime(), a, OffsetTime.now(CLOCK).minusNanos(1), OffsetTime.now(CLOCK), OffsetTime.now(CLOCK).plusNanos(1), null)).as("OffsetTime").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForYear(), a, Year.of(2025), Year.of(2026), Year.of(2027), null)).as("Year").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForYearMonth(), a, YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 7), null)).as("YearMonth").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForZonedDateTime(), a, ZonedDateTime.now(CLOCK).minusNanos(1), ZonedDateTime.now(CLOCK), ZonedDateTime.now(CLOCK).plusNanos(1), null)).as("ZonedDateTime").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForHijrahDate(), a, HijrahDate.now(CLOCK).minus(1, ChronoUnit.DAYS), HijrahDate.now(CLOCK), HijrahDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("HijrahDate").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForJapaneseDate(), a, JapaneseDate.now(CLOCK).minus(1, ChronoUnit.DAYS), JapaneseDate.now(CLOCK), JapaneseDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("JapaneseDate").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForMinguoDate(), a, MinguoDate.now(CLOCK).minus(1, ChronoUnit.DAYS), MinguoDate.now(CLOCK), MinguoDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("MinguoDate").isEqualTo("TFFT");
        assertThat(pattern(new PastValidatorForThaiBuddhistDate(), a, ThaiBuddhistDate.now(CLOCK).minus(1, ChronoUnit.DAYS), ThaiBuddhistDate.now(CLOCK), ThaiBuddhistDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("ThaiBuddhistDate").isEqualTo("TFFT");
    }

    @Test
    void pastOrPresent() {
        var a = annotation(F.class, "pastOrPresent", PastOrPresent.class);
        assertThat(pattern(new PastOrPresentValidatorForDate(), a, new Date(MS - 1), new Date(MS), new Date(MS + 1), null)).as("Date").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForCalendar(), a, calendar(MS - 1), calendar(MS), calendar(MS + 1), null)).as("Calendar").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForInstant(), a, NOW.minusNanos(1), NOW, NOW.plusNanos(1), null)).as("Instant").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForLocalDate(), a, LocalDate.now(CLOCK).minusDays(1), LocalDate.now(CLOCK), LocalDate.now(CLOCK).plusDays(1), null)).as("LocalDate").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForLocalDateTime(), a, LocalDateTime.now(CLOCK).minusNanos(1), LocalDateTime.now(CLOCK), LocalDateTime.now(CLOCK).plusNanos(1), null)).as("LocalDateTime").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForLocalTime(), a, LocalTime.now(CLOCK).minusNanos(1), LocalTime.now(CLOCK), LocalTime.now(CLOCK).plusNanos(1), null)).as("LocalTime").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForMonthDay(), a, MonthDay.of(6, 14), MonthDay.of(6, 15), MonthDay.of(6, 16), null)).as("MonthDay").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForOffsetDateTime(), a, OffsetDateTime.now(CLOCK).minusNanos(1), OffsetDateTime.now(CLOCK), OffsetDateTime.now(CLOCK).plusNanos(1), null)).as("OffsetDateTime").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForOffsetTime(), a, OffsetTime.now(CLOCK).minusNanos(1), OffsetTime.now(CLOCK), OffsetTime.now(CLOCK).plusNanos(1), null)).as("OffsetTime").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForYear(), a, Year.of(2025), Year.of(2026), Year.of(2027), null)).as("Year").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForYearMonth(), a, YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 7), null)).as("YearMonth").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForZonedDateTime(), a, ZonedDateTime.now(CLOCK).minusNanos(1), ZonedDateTime.now(CLOCK), ZonedDateTime.now(CLOCK).plusNanos(1), null)).as("ZonedDateTime").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForHijrahDate(), a, HijrahDate.now(CLOCK).minus(1, ChronoUnit.DAYS), HijrahDate.now(CLOCK), HijrahDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("HijrahDate").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForJapaneseDate(), a, JapaneseDate.now(CLOCK).minus(1, ChronoUnit.DAYS), JapaneseDate.now(CLOCK), JapaneseDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("JapaneseDate").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForMinguoDate(), a, MinguoDate.now(CLOCK).minus(1, ChronoUnit.DAYS), MinguoDate.now(CLOCK), MinguoDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("MinguoDate").isEqualTo("TTFT");
        assertThat(pattern(new PastOrPresentValidatorForThaiBuddhistDate(), a, ThaiBuddhistDate.now(CLOCK).minus(1, ChronoUnit.DAYS), ThaiBuddhistDate.now(CLOCK), ThaiBuddhistDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("ThaiBuddhistDate").isEqualTo("TTFT");
    }

    @Test
    void future() {
        var a = annotation(F.class, "future", Future.class);
        assertThat(pattern(new FutureValidatorForDate(), a, new Date(MS - 1), new Date(MS), new Date(MS + 1), null)).as("Date").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForCalendar(), a, calendar(MS - 1), calendar(MS), calendar(MS + 1), null)).as("Calendar").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForInstant(), a, NOW.minusNanos(1), NOW, NOW.plusNanos(1), null)).as("Instant").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForLocalDate(), a, LocalDate.now(CLOCK).minusDays(1), LocalDate.now(CLOCK), LocalDate.now(CLOCK).plusDays(1), null)).as("LocalDate").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForLocalDateTime(), a, LocalDateTime.now(CLOCK).minusNanos(1), LocalDateTime.now(CLOCK), LocalDateTime.now(CLOCK).plusNanos(1), null)).as("LocalDateTime").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForLocalTime(), a, LocalTime.now(CLOCK).minusNanos(1), LocalTime.now(CLOCK), LocalTime.now(CLOCK).plusNanos(1), null)).as("LocalTime").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForMonthDay(), a, MonthDay.of(6, 14), MonthDay.of(6, 15), MonthDay.of(6, 16), null)).as("MonthDay").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForOffsetDateTime(), a, OffsetDateTime.now(CLOCK).minusNanos(1), OffsetDateTime.now(CLOCK), OffsetDateTime.now(CLOCK).plusNanos(1), null)).as("OffsetDateTime").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForOffsetTime(), a, OffsetTime.now(CLOCK).minusNanos(1), OffsetTime.now(CLOCK), OffsetTime.now(CLOCK).plusNanos(1), null)).as("OffsetTime").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForYear(), a, Year.of(2025), Year.of(2026), Year.of(2027), null)).as("Year").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForYearMonth(), a, YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 7), null)).as("YearMonth").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForZonedDateTime(), a, ZonedDateTime.now(CLOCK).minusNanos(1), ZonedDateTime.now(CLOCK), ZonedDateTime.now(CLOCK).plusNanos(1), null)).as("ZonedDateTime").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForHijrahDate(), a, HijrahDate.now(CLOCK).minus(1, ChronoUnit.DAYS), HijrahDate.now(CLOCK), HijrahDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("HijrahDate").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForJapaneseDate(), a, JapaneseDate.now(CLOCK).minus(1, ChronoUnit.DAYS), JapaneseDate.now(CLOCK), JapaneseDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("JapaneseDate").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForMinguoDate(), a, MinguoDate.now(CLOCK).minus(1, ChronoUnit.DAYS), MinguoDate.now(CLOCK), MinguoDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("MinguoDate").isEqualTo("FFTT");
        assertThat(pattern(new FutureValidatorForThaiBuddhistDate(), a, ThaiBuddhistDate.now(CLOCK).minus(1, ChronoUnit.DAYS), ThaiBuddhistDate.now(CLOCK), ThaiBuddhistDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("ThaiBuddhistDate").isEqualTo("FFTT");
    }

    @Test
    void futureOrPresent() {
        var a = annotation(F.class, "futureOrPresent", FutureOrPresent.class);
        assertThat(pattern(new FutureOrPresentValidatorForDate(), a, new Date(MS - 1), new Date(MS), new Date(MS + 1), null)).as("Date").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForCalendar(), a, calendar(MS - 1), calendar(MS), calendar(MS + 1), null)).as("Calendar").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForInstant(), a, NOW.minusNanos(1), NOW, NOW.plusNanos(1), null)).as("Instant").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForLocalDate(), a, LocalDate.now(CLOCK).minusDays(1), LocalDate.now(CLOCK), LocalDate.now(CLOCK).plusDays(1), null)).as("LocalDate").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForLocalDateTime(), a, LocalDateTime.now(CLOCK).minusNanos(1), LocalDateTime.now(CLOCK), LocalDateTime.now(CLOCK).plusNanos(1), null)).as("LocalDateTime").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForLocalTime(), a, LocalTime.now(CLOCK).minusNanos(1), LocalTime.now(CLOCK), LocalTime.now(CLOCK).plusNanos(1), null)).as("LocalTime").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForMonthDay(), a, MonthDay.of(6, 14), MonthDay.of(6, 15), MonthDay.of(6, 16), null)).as("MonthDay").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForOffsetDateTime(), a, OffsetDateTime.now(CLOCK).minusNanos(1), OffsetDateTime.now(CLOCK), OffsetDateTime.now(CLOCK).plusNanos(1), null)).as("OffsetDateTime").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForOffsetTime(), a, OffsetTime.now(CLOCK).minusNanos(1), OffsetTime.now(CLOCK), OffsetTime.now(CLOCK).plusNanos(1), null)).as("OffsetTime").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForYear(), a, Year.of(2025), Year.of(2026), Year.of(2027), null)).as("Year").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForYearMonth(), a, YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 7), null)).as("YearMonth").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForZonedDateTime(), a, ZonedDateTime.now(CLOCK).minusNanos(1), ZonedDateTime.now(CLOCK), ZonedDateTime.now(CLOCK).plusNanos(1), null)).as("ZonedDateTime").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForHijrahDate(), a, HijrahDate.now(CLOCK).minus(1, ChronoUnit.DAYS), HijrahDate.now(CLOCK), HijrahDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("HijrahDate").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForJapaneseDate(), a, JapaneseDate.now(CLOCK).minus(1, ChronoUnit.DAYS), JapaneseDate.now(CLOCK), JapaneseDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("JapaneseDate").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForMinguoDate(), a, MinguoDate.now(CLOCK).minus(1, ChronoUnit.DAYS), MinguoDate.now(CLOCK), MinguoDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("MinguoDate").isEqualTo("FTTT");
        assertThat(pattern(new FutureOrPresentValidatorForThaiBuddhistDate(), a, ThaiBuddhistDate.now(CLOCK).minus(1, ChronoUnit.DAYS), ThaiBuddhistDate.now(CLOCK), ThaiBuddhistDate.now(CLOCK).plus(1, ChronoUnit.DAYS), null)).as("ThaiBuddhistDate").isEqualTo("FTTT");
    }

    @Test
    void localTypesAreComparedInTheClockZone() {
        var past = annotation(F.class, "past", Past.class);
        var future = annotation(F.class, "future", Future.class);
        // The clock reads 21:00 in Tokyo although it is 12:00 UTC.
        assertThat(pattern(new PastValidatorForLocalDateTime(), past, java.time.LocalDateTime.of(2026, 6, 15, 20, 59), java.time.LocalDateTime.of(2026, 6, 15, 21, 1))).isEqualTo("TF");
        assertThat(pattern(new FutureValidatorForLocalDateTime(), future, java.time.LocalDateTime.of(2026, 6, 15, 20, 59), java.time.LocalDateTime.of(2026, 6, 15, 21, 1))).isEqualTo("FT");
        assertThat(pattern(new PastValidatorForLocalTime(), past, java.time.LocalTime.of(20, 59), java.time.LocalTime.of(21, 1))).isEqualTo("TF");
    }

    @Test
    void zonedTypesAreComparedAsInstants() {
        var past = annotation(F.class, "past", Past.class);
        var pastOrPresent = annotation(F.class, "pastOrPresent", PastOrPresent.class);
        var sameInstantUtc = OffsetDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);
        var earlierInstantFarEast = OffsetDateTime.of(2026, 6, 15, 20, 59, 0, 0, ZoneOffset.ofHours(9));
        assertThat(pattern(new PastValidatorForOffsetDateTime(), past, sameInstantUtc, earlierInstantFarEast)).isEqualTo("FT");
        assertThat(pattern(new PastOrPresentValidatorForOffsetDateTime(), pastOrPresent, sameInstantUtc)).isEqualTo("T");
        assertThat(pattern(new PastOrPresentValidatorForZonedDateTime(), pastOrPresent, sameInstantUtc.toZonedDateTime(), sameInstantUtc.plusSeconds(1).toZonedDateTime())).isEqualTo("TF");
    }

    @Test
    void dateAndCalendarAreConvertedToInstants() {
        var past = annotation(F.class, "past", Past.class);
        // java.sql.Date does not support toInstant(): the validator must rely on the epoch millis.
        assertThat(pattern(new PastValidatorForDate(), past, legacyDate(MS - 1), legacyDate(MS + 1))).isEqualTo("TF");
        assertThat(pattern(new PastValidatorForCalendar(), past, calendar(MS - 1), calendar(MS))).isEqualTo("TF");
    }
}
