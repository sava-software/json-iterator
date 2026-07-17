package systems.comodal.jsoniter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeParseException;

import static java.time.Instant.ofEpochSecond;
import static systems.comodal.jsoniter.BaseJsonIterator.intDigit;
import static systems.comodal.jsoniter.BaseJsonIterator.INVALID_CHAR_FOR_NUMBER;

public final class InstantParser {

  private InstantParser() {
  }

  private static final int SECONDS_PER_HOUR = 60 * 60;
  /**
   * The number of days in a 400 year cycle.
   */
  private static final int DAYS_PER_CYCLE = 146097;
  /**
   * The number of days from year zero to year 1970.
   * There are five 400 year cycles from year zero to 2000.
   * There are 7 leap years from 1970 to 2000.
   */
  private static final long DAYS_0000_TO_1970 = (DAYS_PER_CYCLE * 5L) - (30L * 365L + 7L);

  private static long toEpochSecond(final long year,
                                    final long month,
                                    final int day,
                                    final int hour,
                                    final int minute,
                                    final int second) {
    long total = 365 * year;
    if (year >= 0) {
      total += (year + 3) / 4 - (year + 99) / 100 + (year + 399) / 400;
    } else {
      total -= year / -4 - year / -100 + year / -400;
    }
    total += ((367 * month - 362) / 12);
    total += day - 1;
    if (month > 2) {
      total--;
      if (!IsoChronology.INSTANCE.isLeapYear(year)) {
        total--;
      }
    }
    return (86400 * (total - DAYS_0000_TO_1970))
        + ((long) hour * SECONDS_PER_HOUR)
        + (minute * 60L)
        + second;
  }

  private static DateTimeParseException throwDateTimeParseException(final String context,
                                                                    final char[] buf,
                                                                    final int begin,
                                                                    final int len,
                                                                    final int offset) {
    final var dateTime = new String(buf, begin, len);
    throw new DateTimeParseException(context + '[' + dateTime + ']', dateTime, offset);
  }

  private static int parseOffset(final char[] buf,
                                 int i,
                                 final int offset,
                                 final int len,
                                 final int max) {
    // each field is read at fixed positions past the sign or separator, so a
    // truncated tail must reject before indexing past the value
    if (i + 2 >= max) {
      throw throwDateTimeParseException("Invalid offset ", buf, offset, len, len);
    }
    int hourOffset = intDigit(buf[++i]);
    if (hourOffset == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid offset ", buf, offset, len, i - offset);
    }
    int ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid offset ", buf, offset, len, i - offset);
    }
    hourOffset = SECONDS_PER_HOUR * ((hourOffset << 3) + (hourOffset << 1) + ind);
    if (++i == max) {
      return hourOffset;
    }
    if (i + 2 >= max) {
      throw throwDateTimeParseException("Invalid offset ", buf, offset, len, len);
    }
    int minuteOffset = intDigit(buf[++i]);
    if (minuteOffset == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid offset ", buf, offset, len, i - offset);
    }
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid offset ", buf, offset, len, i - offset);
    }
    minuteOffset = 60 * ((minuteOffset << 3) + (minuteOffset << 1) + ind);
    return hourOffset + minuteOffset;
  }

  public static final CharBufferFunction<Instant> RFC_1123_INSTANT_PARSER = (buf, offset, len) -> {
    // fields sit at fixed positions through offset + 24 and the zone begins at
    // offset + 26; anything shorter cannot hold the format and would read past
    // the value (or size the zone String negatively)
    if (len < 27) {
      throw throwDateTimeParseException(String.format("Invalid length, %d, expected at least 27 characters", len), buf, offset, len, 0);
    }
    int i = offset + 5;
    int day = intDigit(buf[i]);
    if (day == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid day ", buf, offset, len, i - offset);
    }
    int ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid day ", buf, offset, len, i - offset);
    }
    day = (day << 3) + (day << 1) + ind;
    i += 2;
    char a = buf[i];
    char b = buf[++i];
    char c = buf[++i];
    final int month;
    if (a == 'J') {
      if (b == 'a' && c == 'n') {
        month = 1;
      } else if (b == 'u') {
        if (c == 'l') {
          month = 7;
        } else if (c == 'n') {
          month = 6;
        } else {
          throw throwDateTimeParseException("Invalid month ", buf, offset, len, i - offset);
        }
      } else {
        throw throwDateTimeParseException("Invalid month ", buf, offset, len, i - offset);
      }
    } else if (a == 'M') {
      if (b == 'a') {
        if (c == 'r') {
          month = 3;
        } else if (c == 'y') {
          month = 5;
        } else {
          throw throwDateTimeParseException("Invalid month ", buf, offset, len, i - offset);
        }
      } else {
        throw throwDateTimeParseException("Invalid month ", buf, offset, len, i - offset);
      }
    } else if (a == 'A') {
      if (b == 'p' && c == 'r') {
        month = 4;
      } else if (b == 'u' && c == 'g') {
        month = 8;
      } else {
        throw throwDateTimeParseException("Invalid month ", buf, offset, len, i - offset);
      }
    } else if (a == 'F' && b == 'e' && c == 'b') {
      month = 2;
    } else if (a == 'S' && b == 'e' && c == 'p') {
      month = 9;
    } else if (a == 'O' && b == 'c' && c == 't') {
      month = 10;
    } else if (a == 'N' && b == 'o' && c == 'v') {
      month = 11;
    } else if (a == 'D' && b == 'e' && c == 'c') {
      month = 12;
    } else {
      throw throwDateTimeParseException("Invalid month ", buf, offset, len, i - offset);
    }
    i += 2;
    int year = intDigit(buf[i]);
    if (year == INVALID_CHAR_FOR_NUMBER) {

      throw throwDateTimeParseException("Invalid year ", buf, offset, len, 0);
    }
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid year ", buf, offset, len, i - offset);
    }
    year = (year << 3) + (year << 1) + ind;
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid year ", buf, offset, len, i - offset);
    }
    year = (year << 3) + (year << 1) + ind;
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid year ", buf, offset, len, i - offset);
    }
    year = (year << 3) + (year << 1) + ind;
    i += 2;
    int hour = intDigit(buf[i]);
    if (hour == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid hour ", buf, offset, len, i - offset);
    }
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid hour ", buf, offset, len, i - offset);
    }
    hour = (hour << 3) + (hour << 1) + ind;
    i += 2;
    int minute = intDigit(buf[i]);
    if (minute == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid minute ", buf, offset, len, i - offset);
    }
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid minute ", buf, offset, len, i - offset);
    }
    minute = (minute << 3) + (minute << 1) + ind;
    i += 2;
    int second = intDigit(buf[i]);
    if (second == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid second ", buf, offset, len, i - offset);
    }
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid second ", buf, offset, len, i - offset);
    }
    second = (second << 3) + (second << 1) + ind;
    i += 2;
    final int zoneLength = (offset + len) - i;
    if (zoneLength == 3 && buf[i] == 'G' && buf[i + 1] == 'M' && buf[i + 2] == 'T') {
      // The overwhelmingly common RFC-1123 zone: compute the epoch directly,
      // exactly as the ISO path does, instead of allocating the zone String
      // and going through ZoneId resolution and ZonedDateTime construction.
      return ofEpochSecond(toEpochSecond(year, month, day, hour, minute, second), 0);
    }
    final var zone = ZoneId.of(new String(buf, i, zoneLength));
    return ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone).toInstant();
  };

  public static final CharBufferFunction<Instant> INSTANT_PARSER = (buf, offset, len) -> {
    if (len < 19) {
      if (len == 0) {
        return null;
      } else {
        throw throwDateTimeParseException(String.format("Invalid length, %d, expected at least 19 characters", len), buf, offset, len, 0);
      }
    }
    int i = offset;
    char c = buf[i];
    int year = intDigit(c);
    if (year == INVALID_CHAR_FOR_NUMBER) {
      if (c == 'S' || c == 'T' || c == 'M' || c == 'W' || c == 'F') {
        return RFC_1123_INSTANT_PARSER.apply(buf, offset, len);
      } else {
        throw throwDateTimeParseException("Invalid year ", buf, offset, len, 0);
      }
    }
    int ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid year ", buf, offset, len, i - offset);
    }
    year = (year << 3) + (year << 1) + ind;
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid year ", buf, offset, len, i - offset);
    }
    year = (year << 3) + (year << 1) + ind;
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid year ", buf, offset, len, i - offset);
    }
    year = (year << 3) + (year << 1) + ind;
    i += 2;
    int month = intDigit(buf[i]);
    if (month == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid month ", buf, offset, len, i - offset);
    }
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid month ", buf, offset, len, i - offset);
    }
    month = (month << 3) + (month << 1) + ind;
    i += 2;
    int day = intDigit(buf[i]);
    if (day == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid day ", buf, offset, len, i - offset);
    }
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid day ", buf, offset, len, i - offset);
    }
    day = (day << 3) + (day << 1) + ind;
    i += 2;
    int hour = intDigit(buf[i]);
    if (hour == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid hour ", buf, offset, len, i - offset);
    }
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid hour ", buf, offset, len, i - offset);
    }
    hour = (hour << 3) + (hour << 1) + ind;
    i += 2;
    int minute = intDigit(buf[i]);
    if (minute == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid minute ", buf, offset, len, i - offset);
    }
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid minute ", buf, offset, len, i - offset);
    }
    minute = (minute << 3) + (minute << 1) + ind;
    i += 2;
    int second = intDigit(buf[i]);
    if (second == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid second ", buf, offset, len, i - offset);
    }
    ind = intDigit(buf[++i]);
    if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid second ", buf, offset, len, i - offset);
    }
    second = (second << 3) + (second << 1) + ind;
    final int max = offset + len;
    if (++i == max) {
      return ofEpochSecond(toEpochSecond(year, month, day, hour, minute, second), 0);
    }
    c = buf[i];
    if (c == '.') {
      if (++i == max) {
        throw throwDateTimeParseException("Invalid fraction ", buf, offset, len, i - offset);
      }
      c = buf[i];
    } else {
      final int offsetSeconds;
      if (c == 'Z') {
        offsetSeconds = 0;
      } else if (c == '-') {
        offsetSeconds = -parseOffset(buf, i, offset, len, max);
      } else if (c == '+') {
        offsetSeconds = parseOffset(buf, i, offset, len, max);
      } else {
        throw throwDateTimeParseException("Invalid offset ", buf, offset, len, i - offset);
      }
      return ofEpochSecond(toEpochSecond(year, month, day, hour, minute, second) - offsetSeconds, 0);
    }
    int nano = intDigit(c);
    if (nano == INVALID_CHAR_FOR_NUMBER) {
      throw throwDateTimeParseException("Invalid offset ", buf, offset, len, i - offset);
    }
    int nanoDigitCount = 1;
    int offsetSeconds = 0;
    while (++i < max) {
      c = buf[i];
      ind = intDigit(c);
      if (ind == INVALID_CHAR_FOR_NUMBER) {
        if (c == 'Z') {
          break;
        } else if (c == '-') {
          offsetSeconds = -parseOffset(buf, i, offset, len, max);
          break;
        } else if (c == '+') {
          offsetSeconds = parseOffset(buf, i, offset, len, max);
          break;
        } else {
          throw throwDateTimeParseException("Invalid offset ", buf, offset, len, i - offset);
        }
      }
      nano = (nano << 3) + (nano << 1) + ind;
      nanoDigitCount++;
    }
    if (nanoDigitCount > 9) {
      // More digits than nanosecond precision would silently roll the excess
      // into the seconds (or overflow the accumulator entirely).
      throw throwDateTimeParseException("Invalid fraction ", buf, offset, len, i - offset);
    }
    while (nanoDigitCount++ < 9) {
      nano = (nano << 3) + (nano << 1);
    }
    return ofEpochSecond(toEpochSecond(year, month, day, hour, minute, second) - offsetSeconds, nano);
  };
}
