namespace java com.microapp.autumn.api

service ControlApi {
    string health();
	string applicationInfo();
	string providerInfo();
	string referenceConfig(1: required string name);
    string check(1:required string ip, 2: required i32 port);
}