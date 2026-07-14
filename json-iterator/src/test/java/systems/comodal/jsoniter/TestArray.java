package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestArray {

  private final JsonIteratorFactory factory;

  TestArray(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_empty_array() {
    final var json = "[]";

    var ji = factory.create(json);
    assertFalse(ji.readArray());

    ji = factory.create(json);
    assertEquals(ji, ji.openArray());
    assertEquals(ji, ji.closeArray());
  }

  @Test
  void test_one_element() {
    final var json = "[1]";

    var ji = factory.create(json);
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    assertFalse(ji.readArray());

    ji = factory.create(json);
    assertEquals(ji, ji.openArray());
    assertEquals(1, ji.readInt());
    assertEquals(ji, ji.closeArray());
  }

  @Test
  void test_two_elements() {
    final var json = " [ 1 , 2 ] ";

    var ji = factory.create(json);
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());

    ji = factory.create(json);
    assertEquals(ji, ji.openArray());
    assertEquals(1, ji.readInt());
    assertEquals(ji, ji.continueArray());
    assertEquals(2, ji.readInt());
    assertEquals(ji, ji.closeArray());
  }

  @Test
  void test_three_elements() {
    final var json = " [ 1 , 2, 3 ] ";

    var ji = factory.create(json);
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(3, ji.readInt());
    assertFalse(ji.readArray());

    ji = factory.create(json);
    assertEquals(ji, ji.openArray());
    assertEquals(1, ji.readInt());
    assertEquals(ji, ji.continueArray());
    assertEquals(2, ji.readInt());
    assertEquals(ji, ji.continueArray());
    assertEquals(3, ji.readInt());
    assertEquals(ji, ji.closeArray());
  }

  @Test
  void test_four_elements() {
    final var json = " [ 1 , 2, 3, 4 ] ";
    final var ji = factory.create(json);
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(3, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(4, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_five_elements() {
    final var json = " [ 1 , 2, 3, 4, 5  ] ";
    final var ji = factory.create(json);
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(3, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(4, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(5, ji.readInt());
    assertFalse(ji.readArray());
  }

  private record Point(int x, int y) {

    static Point parse(final JsonIterator ji) {
      final int[] xy = new int[2];
      ji.testObject(xy, (context, buf, offset, len, jsonIterator) -> {
        if (JsonIterator.fieldEquals("x", buf, offset, len)) {
          context[0] = jsonIterator.readInt();
        } else if (JsonIterator.fieldEquals("y", buf, offset, len)) {
          context[1] = jsonIterator.readInt();
        } else {
          jsonIterator.skip();
        }
        return true;
      });
      return new Point(xy[0], xy[1]);
    }
  }

  @Test
  void test_read_list() {
    var ji = factory.create(" [ 1 , 2, 3 ] ");
    assertEquals(List.of(1, 2, 3), ji.readList(JsonIterator::readInt));

    ji = factory.create("""
        ["a","b","c"]""");
    assertEquals(List.of("a", "b", "c"), ji.readList(JsonIterator::readString));
  }

  @Test
  void test_read_list_empty() {
    final var ji = factory.create("[]");
    final var list = ji.readList(JsonIterator::readInt);
    assertTrue(list.isEmpty());
  }

  @Test
  void test_read_list_null() {
    final var ji = factory.create("null");
    final var list = ji.readList(JsonIterator::readInt);
    assertTrue(list.isEmpty());
  }

  @Test
  void test_read_list_null_guard() {
    var ji = factory.create("null");
    assertNull(ji.notNull() ? ji.readList(JsonIterator::readInt) : null);

    ji = factory.create("[7]");
    assertEquals(List.of(7), ji.notNull() ? ji.readList(JsonIterator::readInt) : null);
  }

  @Test
  void test_read_list_of_objects() {
    final var ji = factory.create("""
        [{"x":1,"y":2},{"y":4,"x":3},{}]""");
    assertEquals(
        List.of(new Point(1, 2), new Point(3, 4), new Point(0, 0)),
        ji.readList(Point::parse)
    );
  }

  @Test
  void test_read_list_nested() {
    final var ji = factory.create("[[1,2],[],[3]]");
    assertEquals(
        List.of(List.of(1, 2), List.of(), List.of(3)),
        ji.readList(elementJi -> elementJi.readList(JsonIterator::readInt))
    );
  }

  @Test
  void test_read_collection() {
    final var ji = factory.create("""
        ["a","b","a"]""");
    final var set = ji.readCollection(new HashSet<>(), JsonIterator::readString);
    assertEquals(new HashSet<>(List.of("a", "b")), set);
  }

  @Test
  void test_read_list_as_field_value() {
    final var ji = factory.create("""
        {"ints":[1,2],"after":3}""");
    assertEquals(List.of(1, 2), ji.skipUntil("ints").readList(JsonIterator::readInt));
    assertEquals(3, ji.skipUntil("after").readInt());
  }

  @Test
  void test_read_map_indexed_by_value_field() {
    final var ji = factory.create("""
        [{"x":1,"y":2},{"x":3,"y":4}]""");
    assertEquals(
        Map.of(1, new Point(1, 2), 3, new Point(3, 4)),
        ji.readMap(Point::parse, Point::x)
    );
  }

  @Test
  void test_read_map_indexed_empty() {
    final var ji = factory.create("[]");
    assertTrue(ji.readMap(Point::parse, Point::x).isEmpty());
  }

  @Test
  void test_read_map_indexed_null() {
    var ji = factory.create("null");
    assertTrue(ji.readMap(Point::parse, Point::x).isEmpty());

    ji = factory.create("null");
    assertNull(ji.notNull() ? ji.readMap(Point::parse, Point::x) : null);
  }

  @Test
  void test_read_map_indexed_duplicate_key_last_wins() {
    final var ji = factory.create("""
        [{"x":1,"y":2},{"x":1,"y":9}]""");
    assertEquals(
        Map.of(1, new Point(1, 9)),
        ji.readMap(Point::parse, Point::x)
    );
  }

  @Test
  void test_read_map_indexed_supplied_map() {
    final var ji = factory.create("""
        [{"x":3,"y":4},{"x":1,"y":2}]""");
    final var map = ji.readMap(new TreeMap<>(), Point::parse, Point::x);
    assertEquals(List.of(1, 3), List.copyOf(map.keySet()));
  }

  @Test
  void test_read_map_indexed_as_field_value() {
    final var ji = factory.create("""
        {"points":[{"x":1,"y":2}],"after":3}""");
    assertEquals(
        Map.of(1, new Point(1, 2)),
        ji.skipUntil("points").readMap(Point::parse, Point::x)
    );
    assertEquals(3, ji.skipUntil("after").readInt());
  }

  @Test
  void test_read_int_array() {
    assertArrayEquals(new int[]{1, -2, 3}, factory.create("[1,-2,3]").readIntArray());
    assertArrayEquals(new int[0], factory.create("[]").readIntArray());
    assertArrayEquals(new int[0], factory.create("null").readIntArray());
    assertArrayEquals(
        new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE},
        factory.create("[2147483647,-2147483648]").readIntArray()
    );
  }

  @Test
  void test_read_int_array_growth() {
    // Sweep across the initial capacity (8) and both doubling boundaries.
    for (int n = 7; n <= 33; ++n) {
      final var expected = new int[n];
      final var json = new StringBuilder("[");
      for (int i = 0; i < n; ++i) {
        expected[i] = i;
        json.append(i).append(',');
      }
      json.setCharAt(json.length() - 1, ']');
      assertArrayEquals(expected, factory.create(json.toString()).readIntArray(), "n=" + n);
    }
  }

  @Test
  void test_read_long_array() {
    assertArrayEquals(new long[]{1, -2, 3}, factory.create("[1,-2,3]").readLongArray());
    assertArrayEquals(new long[0], factory.create("[]").readLongArray());
    assertArrayEquals(new long[0], factory.create("null").readLongArray());
    assertArrayEquals(
        new long[]{Long.MAX_VALUE, Long.MIN_VALUE, 0, 1, 2, 3, 4, 5, 6, 7, 8},
        factory.create("[9223372036854775807,-9223372036854775808,0,1,2,3,4,5,6,7,8]").readLongArray()
    );
  }

  @Test
  void test_read_byte_array() {
    assertArrayEquals(new byte[]{0, 1, 127, (byte) 128, (byte) 255}, factory.create("[0,1,127,128,255]").readByteArray());
    assertArrayEquals(new byte[0], factory.create("[]").readByteArray());
    assertArrayEquals(new byte[0], factory.create("null").readByteArray());

    final var thirtyTwo = new byte[32];
    final var json = new StringBuilder("[");
    for (int i = 0; i < 32; ++i) {
      thirtyTwo[i] = (byte) i;
      json.append(i).append(',');
    }
    json.setCharAt(json.length() - 1, ']');
    assertArrayEquals(thirtyTwo, factory.create(json.toString()).readByteArray());
  }

  @Test
  void test_read_byte_array_into() {
    final var into = new byte[4];
    assertEquals(3, factory.create("[7,8,9]").readByteArray(into));
    assertArrayEquals(new byte[]{7, 8, 9, 0}, into);

    assertEquals(0, factory.create("[]").readByteArray(into));
    assertEquals(0, factory.create("null").readByteArray(into));

    assertThrows(
        ArrayIndexOutOfBoundsException.class,
        () -> factory.create("[1,2,3]").readByteArray(new byte[2])
    );
  }

  @Test
  void test_read_primitive_arrays_as_field_values() {
    final var ji = factory.create("""
        {"ints":[1,2],"longs":[3],"bytes":[4,5],"after":6}""");
    assertArrayEquals(new int[]{1, 2}, ji.skipUntil("ints").readIntArray());
    assertArrayEquals(new long[]{3}, ji.skipUntil("longs").readLongArray());
    assertArrayEquals(new byte[]{4, 5}, ji.skipUntil("bytes").readByteArray());
    assertEquals(6, ji.skipUntil("after").readInt());
  }
}
