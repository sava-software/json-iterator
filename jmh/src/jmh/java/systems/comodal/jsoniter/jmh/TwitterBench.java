package systems.comodal.jsoniter.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.simdjson.JsonValue;
import org.simdjson.SimdJsonParser;
import systems.comodal.jsoniter.JsonIter;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;

/// Compares three parsers over the classic simdjson twitter.json corpus (617 KiB):
/// - `jsonIterator`: this library's original pull parser (vectorized fast paths)
/// - `jsonIter`: this library's two-stage structural-index iterator
/// - `simdjson`: simdjson-java (eager two-stage parse to a tape/DOM)
///
/// Workloads:
/// - `fullWalk`: touch every field name and value in the document.
/// - `screenNames`: extract statuses[*].user.screen_name and skip everything
///   else — the classic simdjson on-demand showcase.
/// - `parseOnly`: the fixed per-document cost — stage 1 indexing for JsonIter,
///   stage 1 + tape construction for simdjson (JsonIterator has no equivalent).
///
/// All implementations are cross-checked for agreement during setup.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class TwitterBench {

  private byte[] json;
  private JsonIterator jsonIterator;
  private JsonIter jsonIter;
  private SimdJsonParser simdParser;

  @Setup
  public void setup() {
    try (final var in = TwitterBench.class.getResourceAsStream("/twitter.json")) {
      json = in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    jsonIterator = JsonIterator.parse(json);
    jsonIter = JsonIter.parse(json);
    simdParser = new SimdJsonParser(json.length + 1_024, 1_024);

    check(fullWalk_jsonIterator(), fullWalk_jsonIter(), fullWalk_simdjson());
    check(screenNames_jsonIterator(), screenNames_jsonIter(), screenNames_simdjson());
  }

  private static void check(final long a, final long b, final long c) {
    if (a != b || b != c) {
      throw new IllegalStateException("parsers disagree: " + a + ", " + b + ", " + c);
    }
  }

  // parseOnly

  @Benchmark
  public Object parseOnly_jsonIter() {
    return jsonIter.reset(json);
  }

  @Benchmark
  public Object parseOnly_simdjson() {
    return simdParser.parse(json, json.length);
  }

  // fullWalk

  @Benchmark
  public long fullWalk_jsonIterator() {
    return walk(jsonIterator.reset(json));
  }

  @Benchmark
  public long fullWalk_jsonIter() {
    return walk(jsonIter.reset(json));
  }

  @Benchmark
  public long fullWalk_simdjson() {
    return walk(simdParser.parse(json, json.length));
  }

  private static long walk(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case OBJECT -> {
        long sum = 0;
        for (var field = ji.readObjField(); field != null; field = ji.readObjField()) {
          sum += field.length() + walk(ji);
        }
        yield sum;
      }
      case ARRAY -> {
        long sum = 0;
        while (ji.readArray()) {
          sum += walk(ji);
        }
        yield sum;
      }
      case STRING -> ji.readString().length();
      case NUMBER -> number(ji.readNumberAsString());
      case BOOLEAN -> ji.readBoolean() ? 1 : 0;
      case NULL -> {
        ji.skip();
        yield 1;
      }
      default -> throw new IllegalStateException(ji.currentBuffer());
    };
  }

  private static long walk(final JsonIter ji) {
    return switch (ji.whatIsNext()) {
      case OBJECT -> {
        long sum = 0;
        for (var field = ji.nextField(); field != null; field = ji.nextField()) {
          sum += field.length() + walk(ji);
        }
        yield sum;
      }
      case ARRAY -> {
        long sum = 0;
        while (ji.readArray()) {
          sum += walk(ji);
        }
        yield sum;
      }
      case STRING -> ji.readString().length();
      case NUMBER -> number(ji.readNumberAsString());
      case BOOLEAN -> ji.readBoolean() ? 1 : 0;
      case NULL -> {
        ji.skip();
        yield 1;
      }
      default -> throw new IllegalStateException(ji.currentBuffer());
    };
  }

  private static long walk(final JsonValue value) {
    if (value.isObject()) {
      long sum = 0;
      for (final var it = value.objectIterator(); it.hasNext(); ) {
        final var field = it.next();
        sum += field.getKey().length() + walk(field.getValue());
      }
      return sum;
    } else if (value.isArray()) {
      long sum = 0;
      for (final var it = value.arrayIterator(); it.hasNext(); ) {
        sum += walk(it.next());
      }
      return sum;
    } else if (value.isString()) {
      return value.asString().length();
    } else if (value.isLong()) {
      return value.asLong();
    } else if (value.isDouble()) {
      return (long) value.asDouble();
    } else if (value.isBoolean()) {
      return value.asBoolean() ? 1 : 0;
    } else {
      return 1; // null
    }
  }

  private static long number(final String number) {
    for (int i = 0; i < number.length(); ++i) {
      final char c = number.charAt(i);
      if (c == '.' || c == 'e' || c == 'E') {
        return (long) Double.parseDouble(number);
      }
    }
    return Long.parseLong(number);
  }

  // screenNames

  @Benchmark
  public long screenNames_jsonIterator() {
    final var ji = jsonIterator.reset(json);
    long sum = 0;
    ji.skipUntil("statuses");
    while (ji.readArray()) {
      ji.skipUntil("user").skipUntil("screen_name");
      sum += ji.readString().length();
      ji.skipRestOfObject().skipRestOfObject();
    }
    return sum;
  }

  @Benchmark
  public long screenNames_jsonIter() {
    final var ji = jsonIter.reset(json);
    long sum = 0;
    ji.skipUntil("statuses");
    while (ji.readArray()) {
      ji.skipUntil("user").skipUntil("screen_name");
      sum += ji.readString().length();
      ji.skipRestOfObject().skipRestOfObject();
    }
    return sum;
  }

  @Benchmark
  public long screenNames_simdjson() {
    final var doc = simdParser.parse(json, json.length);
    long sum = 0;
    for (final var it = doc.get("statuses").arrayIterator(); it.hasNext(); ) {
      sum += it.next().get("user").get("screen_name").asString().length();
    }
    return sum;
  }
}
