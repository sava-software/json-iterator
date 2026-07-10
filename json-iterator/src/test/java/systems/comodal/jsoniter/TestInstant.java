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
  void testInvalidInstants() {
    assertThrows(DateTimeParseException.class, () -> factory.create("\"201x-03-15T01:23:44Z\"").readDateTime());
    assertThrows(DateTimeParseException.class, () -> factory.create("\"2018-0x-15T01:23:44Z\"").readDateTime());
    assertThrows(DateTimeParseException.class, () -> factory.create("\"2018-03-1xT01:23:44Z\"").readDateTime());
    assertThrows(DateTimeParseException.class, () -> factory.create("\"2018-03-15T0x:23:44Z\"").readDateTime());
    assertThrows(DateTimeParseException.class, () -> factory.create("\"2018-03-15T01:2x:44Z\"").readDateTime());
    assertThrows(DateTimeParseException.class, () -> factory.create("\"2018-03-15T01:23:4xZ\"").readDateTime());
    assertThrows(DateTimeParseException.class, () -> factory.create("\"tooshort\"").readDateTime());
  }
}
