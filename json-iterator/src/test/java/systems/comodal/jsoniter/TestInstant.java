package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestInstant {

  private final JsonIteratorFactory factory;

  TestInstant(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void testParseInstants() {
    var dateTime = "2018-03-31T13:43:19.82";
    var ji = factory.create('"' + dateTime + '"');
    assertEquals(ISO_DATE_TIME.withZone(ZoneOffset.UTC).parse(dateTime, Instant::from), ji.readDateTime());

    dateTime = "2018-03-15T01:23:44.349000Z";
    ji = factory.create('"' + dateTime + '"');
    assertEquals(Instant.parse(dateTime), ji.readDateTime());

    dateTime = "2018-04-07T18:27:12.646Z";
    ji = factory.create('"' + dateTime + '"');
    assertEquals(Instant.parse(dateTime), ji.readDateTime());

    dateTime = "2018-03-31T19:48:23.0752385Z";
    ji = factory.create('"' + dateTime + '"');
    assertEquals(Instant.parse(dateTime), ji.readDateTime());

    dateTime = "Fri, 04 Oct 2019 16:06:36 GMT";
    ji = factory.create('"' + dateTime + '"');
    assertEquals(RFC_1123_DATE_TIME.parse(dateTime, Instant::from), ji.readDateTime());
  }

  @Test
  void testParseRfc1123Instants() {
    // GMT takes the direct epoch computation; every case is compared against
    // the java.time formatter.
    for (final var dateTime : new String[]{
        "Thu, 01 Jan 1970 00:00:00 GMT",
        "Wed, 31 Dec 1969 23:59:59 GMT",
        "Sat, 29 Feb 2020 00:00:00 GMT",
        "Tue, 28 Feb 2023 23:59:59 GMT",
        "Fri, 04 Oct 2019 16:06:36 GMT",
        "Thu, 01 Mar 1900 12:00:00 GMT",
        "Fri, 31 Dec 2100 23:59:59 GMT",
        "Sun, 15 Aug 2049 07:30:05 GMT"}) {
      assertEquals(RFC_1123_DATE_TIME.parse(dateTime, Instant::from),
          factory.create('"' + dateTime + '"').readDateTime(), dateTime);
    }
    // non-GMT zones fall back to ZoneId resolution
    for (final var dateTime : new String[]{
        "Fri, 04 Oct 2019 16:06:36 +0200",
        "Fri, 04 Oct 2019 16:06:36 -0830"}) {
      assertEquals(RFC_1123_DATE_TIME.parse(dateTime, Instant::from),
          factory.create('"' + dateTime + '"').readDateTime(), dateTime);
    }
  }

  @Test
  void test_fraction_digits() {
    // nine digits is full nanosecond precision
    assertEquals(Instant.parse("2019-10-04T16:06:36.123456789Z"),
        factory.create("\"2019-10-04T16:06:36.123456789Z\"").readDateTime());
    // more than nine silently rolled the excess into the seconds
    assertThrows(DateTimeParseException.class,
        () -> factory.create("\"2019-10-04T16:06:36.1234567891Z\"").readDateTime());
    // enough digits to overflow the int accumulator entirely
    assertThrows(DateTimeParseException.class,
        () -> factory.create("\"2019-10-04T16:06:36.12345678901234Z\"").readDateTime());
  }

  @Test
  void testInvalidInstants() {
    assertThrows(DateTimeParseException.class, () -> factory.create("\"201x-03-15T01:23:44Z\"").readDateTime());
    assertThrows(DateTimeParseException.class, () -> factory.create("\"2018-0x-15T01:23:44Z\"").readDateTime());
    assertThrows(DateTimeParseException.class, () -> factory.create("\"2018-03-1xT01:23:44Z\"").readDateTime());
    assertThrows(DateTimeParseException.class, () -> factory.create("\"2018-03-15T0x:23:44Z\"").readDateTime());
    assertThrows(DateTimeParseException.class, () -> factory.create("\"2018-03-15T01:2x:44Z\"").readDateTime());
    assertThrows(DateTimeParseException.class, () -> factory.create("\"2018-03-15T01:23:4xZ\"").readDateTime());
    assertThrows(DateTimeParseException.class, () -> factory.create("\"tooshort\"").readDateTime());
  }

  @Test
  void testTruncatedInstants() {
    // fuzz-hardening regression: fixed-position field reads ran past the end
    // of a truncated value — a negative-length zone String on the RFC form,
    // stale buffer chars elsewhere — instead of rejecting
    for (final var dateTime : new String[]{
        // RFC-1123 cut off before, inside, and just after the seconds; no zone
        "Fri, 04 Oct 2019 16", "Fri, 04 Oct 2019 16:06", "Fri, 04 Oct 2019 16:06:36",
        "Fri, 04 Oct 2019 16:06:36 ",
        // ISO with a bare trailing fraction dot
        "2019-10-04T16:06:36.",
        // ISO offsets truncated mid-field
        "2019-10-04T16:06:36+", "2019-10-04T16:06:36+1",
        "2019-10-04T16:06:36+02:", "2019-10-04T16:06:36+02:3",
        "2019-10-04T16:06:36.5+", "2019-10-04T16:06:36.5+02:3"
    }) {
      assertThrows(DateTimeParseException.class, () -> factory.create('"' + dateTime + '"').readDateTime(), dateTime);
    }
    // the short hour-only offset form still parses
    assertEquals(Instant.parse("2019-10-04T14:06:36Z"), factory.create("\"2019-10-04T16:06:36+02\"").readDateTime());
    assertEquals(Instant.parse("2019-10-04T14:06:36.5Z"), factory.create("\"2019-10-04T16:06:36.5+02\"").readDateTime());
  }
}
