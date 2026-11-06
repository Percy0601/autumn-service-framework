namespace java model

// IDL used by the generic-invocation tests; parsed at runtime, never compiled to Java stubs.
enum Status {
  OK = 1,
  FAIL = 2
}

struct Model {
  1: required i32 id,
  2: optional string name = "anon",
  3: list<i32> scores
}

exception ErrorEx {
  1: string message
}

service HelloWorldService {
  string sayHello(1: required string username)
  Model returnModel(1: required string name)
  string modelProcess(1: required Model model)
  i32 sum(1: required list<i32> nums)
  map<string,i32> echoMap(1: required map<string,i32> data)
  void fail(1: required string reason) throws (1: ErrorEx e)
  oneway void ping(1: required string msg)
}
