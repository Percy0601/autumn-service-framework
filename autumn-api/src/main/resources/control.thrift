namespace java com.microapp.autumn.api

service ControlApi {
    // every control plane call carries a shared token; empty token disables authentication
    string health(1: optional string token);
	string applicationInfo(1: optional string token);
	string providerInfo(1: optional string token);
	string referenceConfig(1: required string name, 2: optional string token);
    string check(1: required string ip, 2: required i32 port, 3: optional string token);
}