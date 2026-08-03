package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.factory.JsonIterParser;
import systems.comodal.jsoniter.factory.JsonIterParserFactory;

import java.io.IOException;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/// `JsonIterParserFactory.loadParser` resolves a provider through
/// `ServiceLoader`, which sees different worlds depending on how this suite
/// runs — and this test asserts the correct behavior for whichever world it
/// wakes up in, so it passes deterministically in both:
///
/// - On the **module path** (the `test` task) providers come only from a
///   module's `provides` clause. The fixtures below are patched-in test
///   classes with no such clause, so the loader finds nothing and `loadParser`
///   must throw its no-factory error.
/// - On the **class path** (PIT minions, which always run there) the loader
///   scans `META-INF/services`, and this suite's test resources register the
///   two nested fixture factories — so the resolution pipeline (prefix filter,
///   provider get, parser create) executes and its mutants are killable. The
///   fixtures are nested inside this `Test*` class deliberately: top-level
///   fixtures would silently join the mutated population.
///
/// The fixture naming follows the convention `loadParser(type)` encodes:
/// a provider class named `<Type>ParserFactory` serves parser type `<Type>`.
final class TestParserFactoryLoading {

  public static final class AlphaParserFactory implements JsonIterParserFactory<Alpha> {

    public AlphaParserFactory() {
    }

    @Override
    public JsonIterParser<Alpha> create(final Class<Alpha> parserType) {
      return _ -> new Alpha();
    }
  }

  public static final class BravoParserFactory implements JsonIterParserFactory<Bravo> {

    public BravoParserFactory() {
    }

    @Override
    public JsonIterParser<Bravo> create(final Class<Bravo> parserType) {
      return _ -> new Bravo();
    }
  }

  static final class Alpha {
  }

  static final class Bravo {
  }

  @Test
  void test_load_parser_service_resolution() throws IOException {
    if (ServiceLoader.load(JsonIterParserFactory.class).stream().findAny().isPresent()) {
      // class path: both fixtures registered. The prefix filter must select by
      // name, not take the first provider — Bravo sorts after Alpha in the
      // services file precisely so a dropped or always-true filter answers
      // with the wrong factory.
      assertInstanceOf(Alpha.class, JsonIterParserFactory.loadParser(Alpha.class).parse("{}"));
      assertInstanceOf(Bravo.class, JsonIterParserFactory.loadParser(Bravo.class).parse("{}"));
      assertInstanceOf(Bravo.class, JsonIterParserFactory.loadParser(Bravo.class, "Bravo").parse("{}"));

      final var ex = assertThrows(IllegalArgumentException.class,
          () -> JsonIterParserFactory.loadParser(Bravo.class, "NoSuchPrefix"));
      assertEquals("No parser factory found filtering by name beginning with NoSuchPrefix", ex.getMessage());
    } else {
      // module path: no module provides the service, so resolution must end in
      // the same named error — never a bare NoSuchElement or null
      final var ex = assertThrows(IllegalArgumentException.class,
          () -> JsonIterParserFactory.loadParser(Alpha.class));
      assertEquals("No parser factory found filtering by name beginning with Alpha", ex.getMessage());
    }
  }
}
