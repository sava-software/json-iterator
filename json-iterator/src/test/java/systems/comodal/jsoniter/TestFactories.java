package systems.comodal.jsoniter;

import systems.comodal.jsoniter.factories.ByteArray;
import systems.comodal.jsoniter.factories.CharArray;
import systems.comodal.jsoniter.factories.IndexedByteArray;
import systems.comodal.jsoniter.factories.IndexedCharArray;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.util.List;

final class TestFactories {

  static final List<JsonIteratorFactory> FACTORIES = List.of(ByteArray.INSTANCE, CharArray.INSTANCE, IndexedByteArray.INSTANCE, IndexedCharArray.INSTANCE);
  static final List<JsonIteratorFactory> MARKABLE_FACTORIES = FACTORIES;

  private TestFactories() {
  }
}
