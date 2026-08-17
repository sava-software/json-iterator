package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.IntUnaryOperator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/// Conformance against Nicolas Seriot's JSON Parsing Test Suite — an external
/// accept/reject oracle over RFC 8259, replacing the self-differential checks
/// (byte source vs char source) that the fuzz targets already own. Provenance,
/// the vendored commit, and the argument behind every divergence live in
/// `src/test/resources/jsontestsuite/README.md`.
///
/// The suite tests *documents*, and this library has no whole-document entry
/// point: it is a pull parser, so a caller decides when it is done reading.
/// [#walk] supplies that missing layer — it reads one complete value and then
/// requires the remainder to be whitespace — which is why a rejection records
/// *which* layer produced it. A [Verdict#REJECT] is the library's own
/// [JsonException]; a [Verdict#REJECT_AT_EOF] means the library read a complete
/// value and the trailing input is what made the document invalid.
///
/// The walk is iterative so that arbitrarily deep documents exercise the
/// library's own (iterative) scanning rather than overflowing the harness
/// stack: two corpus cases nest 100,000 and 50,000 levels deep. Object
/// structure therefore goes through [JsonIterator#applyObject(FieldBufferFunction)]
/// one field at a time rather than the recursive `testObject` callback. That
/// keeps the walk iterative while still decoding and validating every key.
final class TestJsonTestSuite {

  private static final String CORPUS = "/jsontestsuite/test_parsing";
  private static final String TABLE = "/jsontestsuite/expected.tsv";

  /// Which layer settled the document, not merely whether it was accepted:
  /// collapsing these two would hide the library accepting a valid prefix of an
  /// invalid document.
  private enum Verdict {

    ACCEPT("accept"),
    REJECT("reject"),
    REJECT_AT_EOF("reject-at-eof");

    private final String token;

    Verdict(final String token) {
      this.token = token;
    }

    static Verdict parse(final String token) {
      for (final var v : values()) {
        if (v.token.equals(token)) {
          return v;
        }
      }
      throw new IllegalArgumentException("unknown verdict: " + token);
    }
  }

  /// `note` is the divergence family from the README, empty when this library
  /// agrees with the suite's own `y_`/`n_` verdict.
  private record Case(String name, Verdict verdict, String note) {

    boolean suiteExpectsAccept() {
      return name.charAt(0) == 'y';
    }

    boolean suiteIsImplementationDefined() {
      return name.charAt(0) == 'i';
    }

    boolean divergesFromSuite() {
      return !suiteIsImplementationDefined() && (verdict == Verdict.ACCEPT) != suiteExpectsAccept();
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static Path resource(final String name) {
    final var url = TestJsonTestSuite.class.getResource(name);
    assertNotNull(url, "missing from test resources: " + name);
    try {
      return Path.of(url.toURI());
    } catch (final URISyntaxException e) {
      throw new AssertionError(e);
    }
  }

  private static List<Case> loadTable() {
    try {
      return Files.readAllLines(resource(TABLE)).stream()
          .filter(line -> !line.isBlank() && line.charAt(0) != '#')
          .map(line -> {
            final var cols = line.split("\t", -1);
            assertTrue(cols.length == 2 || cols.length == 3, () -> "malformed row: " + line);
            return new Case(cols[0], Verdict.parse(cols[1]), cols.length == 3 ? cols[2] : "");
          })
          .toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static final List<Case> CASES = loadTable();

  private static boolean readObjectField(final JsonIterator ji) {
    return ji.applyObject((_, _, _, _) -> Boolean.TRUE) != null;
  }

  /// Reads exactly one value, iteratively, decoding every scalar it passes so
  /// that malformed escapes and literals are rejected rather than skipped over.
  private static void walk(final JsonIterator ji) {
    final var open = new ArrayDeque<Boolean>();
    value:
    for (; ; ) {
      switch (ji.whatIsNext()) {
        case STRING -> ji.readString();
        // readDouble, not readNumberAsString: a token scan is not a parse, and
        // scanning here overstated this library's leniency by 16 cases — tokens
        // like "1+2", "0e" and "-" that the scanner hands back whole are
        // rejected outright once something actually parses them. readDouble is
        // the strictest number read that is valid for every JSON number: the
        // Big readers reject "1.0" and "1e999", which are valid documents.
        case NUMBER -> ji.readDouble();
        case BOOLEAN -> ji.readBoolean();
        case NULL -> ji.skip();
        case ARRAY -> {
          if (ji.readArray()) {
            open.push(Boolean.TRUE);
            continue value;
          }
        }
        case OBJECT -> {
          if (readObjectField(ji)) {
            open.push(Boolean.FALSE);
            continue value;
          }
        }
        case INVALID -> {
          // the contract the fuzz harnesses hold too: nothing accepts a token
          // that whatIsNext could not classify
          ji.skip();
          throw new AssertionError("skip accepted an invalid leading token");
        }
      }
      for (; ; ) {
        final var array = open.peek();
        if (array == null) {
          return;
        }
        if (array ? ji.readArray() : readObjectField(ji)) {
          continue value;
        }
        open.pop();
      }
    }
  }

  /// The whitespace set is the parser's own (`nextToken`), so the check cannot
  /// disagree with it about where the document ended.
  private static boolean trailing(final int from, final int length, final IntUnaryOperator at) {
    for (int i = from; i < length; i++) {
      final int c = at.applyAsInt(i);
      if (c != ' ' && c != '\n' && c != '\t' && c != '\r') {
        return true;
      }
    }
    return false;
  }

  private static Verdict verdict(final JsonIterator ji, final int length, final IntUnaryOperator at) {
    try {
      walk(ji);
    } catch (final JsonException rejected) {
      return Verdict.REJECT;
    }
    return trailing(ji.mark(), length, at) ? Verdict.REJECT_AT_EOF : Verdict.ACCEPT;
  }

  private static Verdict verdictOfBytes(final byte[] doc) {
    return verdict(JsonIterator.parse(doc), doc.length, i -> doc[i] & 0xff);
  }

  private static Verdict verdictOfChars(final char[] doc) {
    return verdict(JsonIterator.parse(doc), doc.length, i -> doc[i]);
  }

  /// null when the bytes are not well-formed UTF-8, which is the only state in
  /// which the two sources are not looking at the same document. Kept private:
  /// a shared top-level test helper would match neither the `Test*` nor the
  /// `*Fuzz*` exclusion and would silently join the mutated population.
  private static char[] decodeStrict(final byte[] doc) {
    final var decoder = UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      final var decoded = decoder.decode(ByteBuffer.wrap(doc));
      final char[] chars = new char[decoded.remaining()];
      decoded.get(chars);
      return chars;
    } catch (final CharacterCodingException notUtf8) {
      return null;
    }
  }

  private static boolean skipAccepts(final byte[] doc) {
    final var ji = JsonIterator.parse(doc);
    try {
      ji.skip();
    } catch (final JsonException rejected) {
      return false;
    }
    return !trailing(ji.mark(), doc.length, i -> doc[i] & 0xff);
  }

  @Test
  void test_walk_decodes_object_field_names() {
    assertEquals(Verdict.REJECT, verdictOfBytes(new byte[]{
        '{', '"', (byte) 0xC0, (byte) 0xAF, '"', ':', '0', '}'
    }), "overlong UTF-8 key");
    assertEquals(Verdict.REJECT, verdictOfBytes(new byte[]{
        '{', '"', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '"', ':', '0', '}'
    }), "UTF-8 spelling of a surrogate key");
  }

  @ParameterizedTest
  @FieldSource("CASES")
  void test_conformance(final Case testCase) throws IOException {
    final byte[] doc = Files.readAllBytes(resource(CORPUS).resolve(testCase.name()));

    assertEquals(testCase.verdict(), verdictOfBytes(doc), () -> "byte source: " + testCase.name());

    final char[] chars = decodeStrict(doc);
    if (chars != null) {
      assertEquals(testCase.verdict(), verdictOfChars(chars), () -> "char source: " + testCase.name());
    }

    // skip() is a structural scan and is deliberately the more lenient of the
    // two paths; what must never happen is the reverse, a document the decoding
    // walk accepts that the scan rejects.
    if (testCase.verdict() == Verdict.ACCEPT) {
      assertTrue(skipAccepts(doc), () -> "skip() rejected a document the walk accepted: " + testCase.name());
    }
  }

  @Test
  void test_table_covers_the_corpus_exactly() throws IOException {
    try (final var files = Files.list(resource(CORPUS))) {
      final var onDisk = files.map(p -> p.getFileName().toString()).sorted().toList();
      final var tabled = CASES.stream().map(Case::name).sorted().toList();
      assertEquals(onDisk, tabled, "every corpus file needs a verdict row and vice versa");
    }
  }

  /// The vendored corpus is a fixed upstream commit (README), so these totals
  /// are constants: a change to any of them means the corpus moved, and the
  /// divergence argument in the README was written against the old one.
  @Test
  void test_corpus_shape_is_the_vendored_one() {
    assertEquals(318, CASES.size());
    assertEquals(95, CASES.stream().filter(Case::suiteExpectsAccept).count());
    assertEquals(35, CASES.stream().filter(Case::suiteIsImplementationDefined).count());
  }

  /// The guard that keeps this suite from becoming a rubber stamp: a row may
  /// only contradict the suite while naming the family that argues for it, so
  /// flipping a verdict silently is not possible — it fails here until someone
  /// writes down why.
  @Test
  void test_every_divergence_carries_its_argument() {
    for (final var testCase : CASES) {
      if (testCase.suiteIsImplementationDefined() || testCase.divergesFromSuite()) {
        assertFalse(testCase.note().isBlank(), () -> "undocumented divergence: " + testCase.name());
      } else {
        assertTrue(testCase.note().isBlank(),
            () -> "note on a row that agrees with the suite: " + testCase.name());
      }
    }
  }

  /// Every RFC-valid document parses. This is the half of the result that is a
  /// flat claim rather than a negotiated one, so it is asserted as a total
  /// rather than left implicit in 95 individual rows. Like the other aggregates
  /// it reads the table, which is a claim about the library only because
  /// [#test_conformance] binds every row to a live parse of its document.
  @Test
  void test_no_valid_document_is_rejected() {
    assertEquals(List.of(), CASES.stream()
        .filter(Case::suiteExpectsAccept)
        .filter(c -> c.verdict() != Verdict.ACCEPT)
        .map(Case::name)
        .toList());
  }

  /// Pins the two families the README argues for, so a *new* leniency cannot
  /// arrive by quietly adding a row to an existing family.
  @Test
  void test_leniency_is_confined_to_the_argued_families() {
    final var lenient = CASES.stream().filter(Case::divergesFromSuite).toList();
    assertEquals(13, lenient.size(), "this library accepts 13 RFC-invalid documents; see README.md");
    assertEquals(10, lenient.stream().filter(c -> c.note().equals("lenient-number-grammar")).count());
    assertEquals(3, lenient.stream().filter(c -> c.note().equals("lenient-unescaped-control")).count());
  }
}
