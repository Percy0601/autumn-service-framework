namespace java com.microapp.autumn.sample.api
struct User {
    1: required i32 userId;
    2: required string username;
    3: optional map<string, string> desc;
}

service SomeService {
	string echo(1: string msg);
    i32 addUser(1: required User user);
    list<User> findUserByIds(1: list<i32> idList);
}

service SomeService2 {
	string echo2(1: string msg);
    i32 addUser2(1: required User user);
    list<User> findUserByIds2(1: list<i32> idList);
}

exception UserNotFoundException {
    1: string message;
}

service SomeService3 {
	string greet(1: string name, 2: i32 age);
    bool updateUser(1: required User user, 2: map<string, string> attrs);
    set<i32> listUserIds();
}

service SomeService4 {
	User getUser(1: i32 userId) throws (1: UserNotFoundException notFound);
    map<string, string> userInfo(1: required User user);
}