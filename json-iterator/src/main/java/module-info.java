module systems.comodal.json_iterator {
  requires jdk.incubator.vector;

  exports systems.comodal.jsoniter;
  exports systems.comodal.jsoniter.factory;

  uses systems.comodal.jsoniter.factory.JsonIterParserFactory;
}
