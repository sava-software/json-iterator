package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static systems.comodal.jsoniter.ContextFieldIndexMaskedPredicate.BREAK_OUT;

/// Arm-by-arm coverage of the five `testObject` overloads, both `applyObject`
/// overloads, and `skipObjField`: first-field (`{`) versus continuation (`,`)
/// branches, empty object, JSON null, break-out on the first and on a later
/// field, and every malformed-document error arm (asserted by operation and
/// message so a mutated arm cannot substitute a different failure).
@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestObjectArms {

  private final JsonIteratorFactory factory;

  TestObjectArms(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  private static final FieldMatcher AB = FieldMatcher.of("a", "b");

  private void assertJsonError(final String json, final String op, final String msgFragment, final Consumer<JsonIterator> walk) {
    final var ji = factory.create(json);
    final var ex = assertThrows(JsonException.class, () -> walk.accept(ji), json);
    assertEquals(op, ex.op(), json);
    assertTrue(ex.getMessage().contains(msgFragment), json + " -> " + ex.getMessage());
  }

  // testObject(FieldBufferPredicate)

  @Test
  void test_test_object_walk() {
    final var seen = new ArrayList<String>();
    final var ji = factory.create("[{\"a\":1,\"b\\tc\":2},7]");
    ji.openArray();
    ji.testObject((buf, offset, len, j) -> {
      final var name = new String(buf, offset, len);
      if ("a".equals(name)) {
        assertTrue(buf.length > len, "unescaped name must be an in-place span");
      }
      seen.add(name + "=" + j.readInt());
      return true;
    });
    assertEquals(List.of("a=1", "b\tc=2"), seen);
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void test_test_object_break_out_first_field() {
    final var ji = factory.create("{\"a\":1,\"b\":2}");
    ji.testObject((_, _, _, _) -> false);
    assertEquals(1, ji.readInt());

    // an escaped name takes the unescape path into the same break-out
    final var escaped = factory.create("{\"a\\tb\":1,\"c\":2}");
    escaped.testObject((buf, offset, len, _) -> {
      assertEquals("a\tb", new String(buf, offset, len));
      return false;
    });
    assertEquals(1, escaped.readInt());
  }

  @Test
  void test_test_object_break_out_later_field() {
    final var ji = factory.create("{\"a\":1,\"b\":2,\"c\":3}");
    ji.testObject((buf, offset, len, j) -> {
      if (JsonIterator.fieldEquals("a", buf, offset, len)) {
        assertEquals(1, j.readInt());
        return true;
      }
      return false;
    });
    assertEquals(2, ji.readInt());
  }

  @Test
  void test_test_object_null() {
    final var ji = factory.create("[null,7]");
    ji.openArray();
    ji.testObject((_, _, _, _) -> fail("null object must not invoke the predicate"));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void test_test_object_error_arms() {
    final FieldBufferPredicate readValue = (_, _, _, j) -> {
      j.readInt();
      return true;
    };
    assertJsonError("{\"a\" 1}", "testObject", "expected :", ji -> ji.testObject(readValue));
    assertJsonError("{\"a\":1,\"b\" 2}", "testObject", "expected :", ji -> ji.testObject(readValue));
    assertJsonError("{\"a\":1,2:3}", "testObject", "expected string field", ji -> ji.testObject(readValue));
    assertJsonError("{1:2}", "testObject", "expected \" after {", ji -> ji.testObject(readValue));
    assertJsonError("[1]", "testObject", "expected [,{}n]", ji -> ji.testObject(readValue));
  }

  // testObject(C, ContextFieldBufferPredicate)

  @Test
  void test_test_object_context_walk() {
    final var seen = new ArrayList<String>();
    final var ji = factory.create("[{\"a\":1,\"b\\tc\":2},7]");
    ji.openArray();
    assertSame(seen, ji.testObject(seen, (context, buf, offset, len, j) -> {
      final var name = new String(buf, offset, len);
      if ("a".equals(name)) {
        assertTrue(buf.length > len, "unescaped name must be an in-place span");
      }
      context.add(name + "=" + j.readInt());
      return true;
    }));
    assertEquals(List.of("a=1", "b\tc=2"), seen);
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void test_test_object_context_break_out_first_field() {
    final var ctx = new Object();
    final var ji = factory.create("{\"a\":1,\"b\":2}");
    assertSame(ctx, ji.testObject(ctx, (_, _, _, _, _) -> false));
    assertEquals(1, ji.readInt());

    final var escaped = factory.create("{\"a\\tb\":1,\"c\":2}");
    assertSame(ctx, escaped.testObject(ctx, (_, buf, offset, len, _) -> {
      assertEquals("a\tb", new String(buf, offset, len));
      return false;
    }));
    assertEquals(1, escaped.readInt());
  }

  @Test
  void test_test_object_context_break_out_later_field() {
    final var ctx = new Object();
    final var ji = factory.create("{\"a\":1,\"b\":2,\"c\":3}");
    assertSame(ctx, ji.testObject(ctx, (_, buf, offset, len, j) -> {
      if (JsonIterator.fieldEquals("a", buf, offset, len)) {
        assertEquals(1, j.readInt());
        return true;
      }
      return false;
    }));
    assertEquals(2, ji.readInt());
  }

  @Test
  void test_test_object_context_empty_and_null() {
    final var ctx = new Object();
    assertSame(ctx, factory.create("{}").testObject(ctx, (_, _, _, _, _) -> fail("no fields to visit")));

    final var ji = factory.create("[null,7]");
    ji.openArray();
    assertSame(ctx, ji.testObject(ctx, (_, _, _, _, _) -> fail("null object must not invoke the predicate")));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void test_test_object_context_error_arms() {
    final ContextFieldBufferPredicate<Object> readValue = (_, _, _, _, j) -> {
      j.readInt();
      return true;
    };
    final var ctx = new Object();
    assertJsonError("{\"a\" 1}", "testObject", "expected :", ji -> ji.testObject(ctx, readValue));
    assertJsonError("{\"a\":1,\"b\" 2}", "testObject", "expected :", ji -> ji.testObject(ctx, readValue));
    assertJsonError("{\"a\":1,2:3}", "testObject", "expected string field", ji -> ji.testObject(ctx, readValue));
    assertJsonError("{1:2}", "testObject", "expected \" after {", ji -> ji.testObject(ctx, readValue));
    assertJsonError("[1]", "testObject", "expected [,{}n]", ji -> ji.testObject(ctx, readValue));
  }

  // testObject(FieldMatcher, FieldIndexPredicate)

  @Test
  void test_test_object_matcher_walk() {
    final var seen = new ArrayList<String>();
    final var ji = factory.create("[{\"a\":1,\"b\":2,\"x\":3},7]");
    ji.openArray();
    ji.testObject(AB, (fieldIndex, j) -> {
      seen.add(fieldIndex + "=" + j.readInt());
      return true;
    });
    assertEquals(List.of("0=1", "1=2", "-1=3"), seen);
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void test_test_object_matcher_break_out() {
    var ji = factory.create("{\"a\":1,\"b\":2}");
    ji.testObject(AB, (_, _) -> false);
    assertEquals(1, ji.readInt());

    ji = factory.create("{\"a\":1,\"b\":2,\"c\":3}");
    ji.testObject(AB, (fieldIndex, j) -> {
      if (fieldIndex == 0) {
        assertEquals(1, j.readInt());
        return true;
      }
      return false;
    });
    assertEquals(2, ji.readInt());
  }

  @Test
  void test_test_object_matcher_null() {
    final var ji = factory.create("[null,7]");
    ji.openArray();
    ji.testObject(AB, (_, _) -> fail("null object must not invoke the predicate"));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void test_test_object_matcher_error_arms() {
    final FieldIndexPredicate readValue = (_, j) -> {
      j.readInt();
      return true;
    };
    assertJsonError("{\"a\" 1}", "testObject", "expected :", ji -> ji.testObject(AB, readValue));
    assertJsonError("{\"a\":1,\"b\" 2}", "testObject", "expected :", ji -> ji.testObject(AB, readValue));
    assertJsonError("{\"a\":1,2:3}", "testObject", "expected string field", ji -> ji.testObject(AB, readValue));
    assertJsonError("{1:2}", "testObject", "expected \" after {", ji -> ji.testObject(AB, readValue));
    assertJsonError("[1]", "testObject", "expected [,{}n]", ji -> ji.testObject(AB, readValue));
  }

  // testObject(C, FieldMatcher, ContextFieldIndexPredicate)

  @Test
  void test_test_object_context_matcher_walk() {
    final var seen = new ArrayList<String>();
    final var ji = factory.create("[{\"a\":1,\"b\":2,\"x\":3},7]");
    ji.openArray();
    assertSame(seen, ji.testObject(seen, AB, (context, fieldIndex, j) -> {
      context.add(fieldIndex + "=" + j.readInt());
      return true;
    }));
    assertEquals(List.of("0=1", "1=2", "-1=3"), seen);
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void test_test_object_context_matcher_break_out() {
    final var ctx = new Object();
    var ji = factory.create("{\"a\":1,\"b\":2}");
    assertSame(ctx, ji.testObject(ctx, AB, (_, _, _) -> false));
    assertEquals(1, ji.readInt());

    ji = factory.create("{\"a\":1,\"b\":2,\"c\":3}");
    assertSame(ctx, ji.testObject(ctx, AB, (_, fieldIndex, j) -> {
      if (fieldIndex == 0) {
        assertEquals(1, j.readInt());
        return true;
      }
      return false;
    }));
    assertEquals(2, ji.readInt());
  }

  @Test
  void test_test_object_context_matcher_empty_null_and_continuation() {
    final var ctx = new Object();
    assertSame(ctx, factory.create("{}").testObject(ctx, AB, (_, _, _) -> fail("no fields to visit")));
    assertSame(ctx, factory.create("}").testObject(ctx, AB, (_, _, _) -> fail("end of object")));

    final var ji = factory.create("[null,7]");
    ji.openArray();
    assertSame(ctx, ji.testObject(ctx, AB, (_, _, _) -> fail("null object must not invoke the predicate")));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void test_test_object_context_matcher_error_arms() {
    final ContextFieldIndexPredicate<Object> readValue = (_, _, j) -> {
      j.readInt();
      return true;
    };
    final var ctx = new Object();
    assertJsonError("{\"a\" 1}", "testObject", "expected :", ji -> ji.testObject(ctx, AB, readValue));
    assertJsonError("{\"a\":1,\"b\" 2}", "testObject", "expected :", ji -> ji.testObject(ctx, AB, readValue));
    assertJsonError("{\"a\":1,2:3}", "testObject", "expected string field", ji -> ji.testObject(ctx, AB, readValue));
    assertJsonError("{1:2}", "testObject", "expected \" after {", ji -> ji.testObject(ctx, AB, readValue));
    assertJsonError("[1]", "testObject", "expected [,{}n]", ji -> ji.testObject(ctx, AB, readValue));
  }

  // testObject(C, FieldMatcher, ContextFieldIndexMaskedPredicate)

  @Test
  void test_test_object_masked_walk_threads_mask() {
    final var masks = new ArrayList<Long>();
    final var ctx = new Object();
    final var ji = factory.create("[{\"a\":1,\"b\":2},7]");
    ji.openArray();
    assertSame(ctx, ji.testObject(ctx, AB, (_, mask, fieldIndex, j) -> {
      masks.add(mask);
      j.readInt();
      return mask | (1L << fieldIndex);
    }));
    assertEquals(List.of(0L, 1L), masks);
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void test_test_object_masked_break_out() {
    final var ctx = new Object();
    var ji = factory.create("{\"a\":1,\"b\":2}");
    assertSame(ctx, ji.testObject(ctx, AB, (_, _, _, _) -> BREAK_OUT));
    assertEquals(1, ji.readInt());

    ji = factory.create("{\"a\":1,\"b\":2,\"c\":3}");
    assertSame(ctx, ji.testObject(ctx, AB, (_, mask, fieldIndex, j) -> {
      if (fieldIndex == 0) {
        assertEquals(1, j.readInt());
        return mask | 1L;
      }
      return BREAK_OUT;
    }));
    assertEquals(2, ji.readInt());
  }

  @Test
  void test_test_object_masked_empty_null_and_continuation() {
    final var ctx = new Object();
    assertSame(ctx, factory.create("{}").testObject(ctx, AB, (_, _, _, _) -> fail("no fields to visit")));
    assertSame(ctx, factory.create("}").testObject(ctx, AB, (_, _, _, _) -> fail("end of object")));

    final var ji = factory.create("[null,7]");
    ji.openArray();
    assertSame(ctx, ji.testObject(ctx, AB, (_, _, _, _) -> fail("null object must not invoke the predicate")));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void test_test_object_masked_error_arms() {
    final ContextFieldIndexMaskedPredicate<Object> readValue = (_, mask, _, j) -> {
      j.readInt();
      return mask;
    };
    final var ctx = new Object();
    assertJsonError("{\"a\" 1}", "testObject", "expected :", ji -> ji.testObject(ctx, AB, readValue));
    assertJsonError("{\"a\":1,\"b\" 2}", "testObject", "expected :", ji -> ji.testObject(ctx, AB, readValue));
    assertJsonError("{\"a\":1,2:3}", "testObject", "expected string field", ji -> ji.testObject(ctx, AB, readValue));
    assertJsonError("{1:2}", "testObject", "expected \" after {", ji -> ji.testObject(ctx, AB, readValue));
    assertJsonError("[1]", "testObject", "expected [,{}n]", ji -> ji.testObject(ctx, AB, readValue));
  }

  // applyObject(FieldBufferFunction)

  @Test
  void test_apply_object_walk() {
    final var ji = factory.create("{\"a\":1,\"b\\tc\":2}");
    assertEquals("a=1", ji.applyObject((buf, offset, len, j) -> {
      assertTrue(buf.length > len, "unescaped name must be an in-place span");
      return new String(buf, offset, len) + "=" + j.readInt();
    }));
    assertEquals("b\tc=2", ji.applyObject((buf, offset, len, j) -> new String(buf, offset, len) + "=" + j.readInt()));
    assertNull(ji.applyObject((_, _, _, _) -> fail("end of object")));
  }

  @Test
  void test_apply_object_empty_and_null() {
    assertNull(factory.create("{}").applyObject((_, _, _, _) -> fail("no fields to visit")));

    final var ji = factory.create("[null,7]");
    ji.openArray();
    assertNull(ji.applyObject((_, _, _, _) -> fail("null object must not invoke the function")));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void test_apply_object_error_arms() {
    final FieldBufferFunction<String> readValue = (_, _, _, j) -> Integer.toString(j.readInt());
    assertJsonError("{\"a\" 1}", "applyObject", "expected :", ji -> ji.applyObject(readValue));
    assertJsonError("{\"a\":1,\"b\" 2}", "applyObject", "expected :", ji -> {
      ji.applyObject(readValue);
      ji.applyObject(readValue);
    });
    assertJsonError("{\"a\":1,2:3}", "applyObject", "expected string field", ji -> {
      ji.applyObject(readValue);
      ji.applyObject(readValue);
    });
    assertJsonError("{1:2}", "applyObject", "expected \" after {", ji -> ji.applyObject(readValue));
    assertJsonError("[1]", "applyObject", "expected [,{}n]", ji -> ji.applyObject(readValue));
  }

  // applyObject(C, ContextFieldBufferFunction)

  @Test
  void test_apply_object_context_walk() {
    final var ctx = new Object();
    final var ji = factory.create("{\"a\":1,\"b\\tc\":2}");
    assertEquals("a=1", ji.applyObject(ctx, (context, buf, offset, len, j) -> {
      assertSame(ctx, context);
      assertTrue(buf.length > len, "unescaped name must be an in-place span");
      return new String(buf, offset, len) + "=" + j.readInt();
    }));
    assertEquals("b\tc=2", ji.applyObject(ctx, (context, buf, offset, len, j) -> {
      assertSame(ctx, context);
      return new String(buf, offset, len) + "=" + j.readInt();
    }));
    assertNull(ji.applyObject(ctx, (_, _, _, _, _) -> fail("end of object")));
  }

  @Test
  void test_apply_object_context_empty_and_null() {
    final var ctx = new Object();
    assertNull(factory.create("{}").applyObject(ctx, (_, _, _, _, _) -> fail("no fields to visit")));

    final var ji = factory.create("[null,7]");
    ji.openArray();
    assertNull(ji.applyObject(ctx, (_, _, _, _, _) -> fail("null object must not invoke the function")));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void test_apply_object_context_error_arms() {
    final ContextFieldBufferFunction<Object, String> readValue = (_, _, _, _, j) -> Integer.toString(j.readInt());
    final var ctx = new Object();
    assertJsonError("{\"a\" 1}", "applyObject", "expected :", ji -> ji.applyObject(ctx, readValue));
    assertJsonError("{\"a\":1,\"b\" 2}", "applyObject", "expected :", ji -> {
      ji.applyObject(ctx, readValue);
      ji.applyObject(ctx, readValue);
    });
    assertJsonError("{\"a\":1,2:3}", "applyObject", "expected string field", ji -> {
      ji.applyObject(ctx, readValue);
      ji.applyObject(ctx, readValue);
    });
    assertJsonError("{1:2}", "applyObject", "expected \" after {", ji -> ji.applyObject(ctx, readValue));
    assertJsonError("[1]", "applyObject", "expected [,{}n]", ji -> ji.applyObject(ctx, readValue));
  }

  // skipObjField

  @Test
  void test_skip_obj_field_arms() {
    final var ji = factory.create("{\"a\":1,\"b\":2}");
    assertSame(ji, ji.skipObjField());
    assertEquals(1, ji.readInt());
    assertSame(ji, ji.skipObjField());
    assertEquals(2, ji.readInt());
    assertNull(ji.skipObjField());

    assertNull(factory.create("{}").skipObjField());

    final var nullJi = factory.create("[null,7]");
    nullJi.openArray();
    assertNull(nullJi.skipObjField());
    assertEquals(7, nullJi.continueArray().readInt());
    nullJi.closeArray();
  }

  @Test
  void test_skip_obj_field_error_arms() {
    assertJsonError("{\"a\" 1}", "skipObjField", "expected :", JsonIterator::skipObjField);
    assertJsonError("{\"a\":1,\"b\" 2}", "skipObjField", "expected :", ji -> {
      ji.skipObjField();
      ji.readInt();
      ji.skipObjField();
    });
    assertJsonError("{\"a\":1,2:3}", "skipObjField", "expected string field", ji -> {
      ji.skipObjField();
      ji.readInt();
      ji.skipObjField();
    });
    assertJsonError("{1:2}", "skipObjField", "expected \" after {", JsonIterator::skipObjField);
    assertJsonError("[1]", "skipObjField", "expected [,{}n]", JsonIterator::skipObjField);
  }
}
