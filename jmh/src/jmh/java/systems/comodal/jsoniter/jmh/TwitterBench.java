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
import systems.comodal.jsoniter.IndexedJsonIterator;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;

/// Compares three parsers over the classic simdjson twitter.json corpus (617 KiB):
/// - `jsonIterator`: this library's original pull parser (vectorized fast paths)
/// - `jsonIndexed`: this library's IndexedJsonIterator (structural-index navigation)
/// - `simdjson`: simdjson-java (eager two-stage parse to a tape/DOM)
///
/// Workloads:
/// - `fullWalk`: touch every field name and value in the document.
/// - `screenNames`: extract statuses[*].user.screen_name and skip everything
///   else — the classic simdjson on-demand showcase.
/// - `parseOnly`: the fixed per-document cost — stage 1 indexing for the
///   indexed iterator, stage 1 + tape construction for simdjson.
///
/// All implementations are cross-checked for agreement during setup.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class TwitterBench {

  private byte[] json;
  private JsonIterator jsonIterator;
  private jsoniter.v21.JsonIterator jsonIterator21;
  private IndexedJsonIterator jsonIndexed;
  private SimdJsonParser simdParser;

  @Setup
  public void setup() {
    try (final var in = TwitterBench.class.getResourceAsStream("/twitter.json")) {
      json = in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    jsonIterator = JsonIterator.parse(json);
    jsonIterator21 = jsoniter.v21.JsonIterator.parse(json);
    jsonIndexed = IndexedJsonIterator.parse(json);
    simdParser = new SimdJsonParser(json.length + 1_024, 1_024);

    check(fullWalk_jsonIterator(), fullWalk_jsonIndexed(), fullWalk_simdjson());
    check(screenNames_jsonIterator(), screenNames_jsonIndexed(), screenNames_simdjson());
    try {
      check(fullWalk_jsonIterator21(), fullWalk_jsonIterator(), fullWalk_jsonIterator());
      check(screenNames_jsonIterator21(), screenNames_jsonIterator(), screenNames_jsonIterator());
    } catch (final RuntimeException e) {
      // 21.0.12 cannot parse twitter.json: its word-at-a-time multi-byte
      // detection matched only bytes exactly 0x80 and desyncs on the first
      // Japanese status. Fixed on main; drop this guard once the fixed
      // version is published and the oldJsonIterator dependency is bumped.
      System.err.println("json-iterator 21.0.12 failed on twitter.json (known multi-byte bug): " + e.getMessage());
    }
  }

  private static void check(final long a, final long b, final long c) {
    if (a != b || b != c) {
      throw new IllegalStateException("parsers disagree: " + a + ", " + b + ", " + c);
    }
  }

  // parseOnly

  @Benchmark
  public Object parseOnly_jsonIndexed() {
    return jsonIndexed.reset(json);
  }

  @Benchmark
  public Object parseOnly_simdjson() {
    return simdParser.parse(json, json.length);
  }

  // fullWalk

  @Benchmark
  public long fullWalk_jsonIterator() {
    return Walks.walk(jsonIterator.reset(json));
  }

  @Benchmark
  public long fullWalk_jsonIterator21() {
    return Walks21.walk(jsonIterator21.reset(json));
  }

  @Benchmark
  public long fullWalk_jsonIndexed() {
    return Walks.walk(jsonIndexed.reset(json));
  }

  @Benchmark
  public long fullWalk_simdjson() {
    return Walks.walk(simdParser.parse(json, json.length));
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
  public long screenNames_jsonIterator21() {
    final var ji = jsonIterator21.reset(json);
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
  public long screenNames_jsonIndexed() {
    final var ji = jsonIndexed.reset(json);
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
