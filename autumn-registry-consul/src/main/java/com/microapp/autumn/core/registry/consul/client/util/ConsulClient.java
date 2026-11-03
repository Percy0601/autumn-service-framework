package com.microapp.autumn.core.registry.consul.client.util;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.apache.hc.core5.util.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.microapp.autumn.api.config.ConsumerConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * consul http client, supports register / deregister / discovery health query.
 *
 * @author: baoxin.zhao
 * @date: 2024/5/12
 */
@Slf4j
public class ConsulClient {
    public static CloseableHttpClient httpClient;

    private static volatile ConsulClient instance;
    private ConsulClient() {

    }
    public static ConsulClient getInstance() {
        if(Objects.isNull(instance)) {
            synchronized (ConsulClient.class) {
                if(Objects.isNull(instance)) {
                    instance = new ConsulClient();
                }
            }
        }
        return instance;
    }

    static {
        RequestConfig config = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(10000L))
                .setResponseTimeout(Timeout.ofMilliseconds(10000L))
                .build();
        try {
            httpClient = HttpClients.custom()
                    .setDefaultRequestConfig(config)
                    .setConnectionManager(getHttpClientConnectionManager())
                    .build();
        } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException e) {
            httpClient = HttpClients.createDefault();
            log.warn("http client config exception: ", e);
        }
    }


    public static String doPut(String url, Map<String, String> uriParams, String json) {
        return doPut(url, uriParams, json, null);
    }

    public static String doPut(String url, Map<String, String> uriParams, String json, Map<String, String> headers) {
        String result = null;
        HttpPut httpPut = new HttpPut(url);
        httpPut.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
        applyHeaders(httpPut, headers);
        if(Objects.nonNull(uriParams) && uriParams.size() > 0) {
            httpPut.setUri(buildUri(url, uriParams));
        }

        HttpClientResponseHandler<String> responseHandler = buildResponseHandler();
        try {
            result = httpClient.execute(httpPut, responseHandler);
        } catch (IOException e) {
            if(log.isWarnEnabled()) {
                log.warn("http client do-put exception:{}", e);
            }
        }
        return result;
    }

    public static String doGet(String url, Map<String, String> uriParams) {
        return doGet(url, uriParams, null);
    }

    public static String doGet(String url, Map<String, String> uriParams, Map<String, String> headers) {
        String result = null;
        HttpGet httpGet = new HttpGet(url);
        applyHeaders(httpGet, headers);
        if(Objects.nonNull(uriParams) && uriParams.size() > 0) {
            httpGet.setUri(buildUri(url, uriParams));
        }
        HttpClientResponseHandler<String> responseHandler = buildResponseHandler();
        try {
            result = httpClient.execute(httpGet, responseHandler);
        } catch (IOException e) {
            if(log.isWarnEnabled()) {
                log.warn("http client do-get exception, url:{}", url, e);
            }
        }
        return result;
    }

    public static String doDelete(String url, Map<String, String> uriParams) {
        return doDelete(url, uriParams, null);
    }

    public static String doDelete(String url, Map<String, String> uriParams, Map<String, String> headers) {
        String result = null;
        HttpDelete httpDelete = new HttpDelete(url);
        applyHeaders(httpDelete, headers);
        if(Objects.nonNull(uriParams) && uriParams.size() > 0) {
            httpDelete.setUri(buildUri(url, uriParams));
        }
        HttpClientResponseHandler<String> responseHandler = buildResponseHandler();
        try {
            result = httpClient.execute(httpDelete, responseHandler);
        } catch (IOException e) {
            if(log.isWarnEnabled()) {
                log.warn("http client do-delete exception, url:{}", url, e);
            }
        }
        return result;
    }

    private static void applyHeaders(org.apache.hc.core5.http.HttpMessage message, Map<String, String> headers) {
        if(Objects.nonNull(headers) && headers.size() > 0) {
            headers.forEach((k, v) -> message.setHeader(k, v));
        }
    }

    private static URI buildUri(String url, Map<String, String> uriParams) {
        List<NameValuePair> nvps = new ArrayList<>();
        uriParams.forEach((k, v) -> nvps.add(new BasicNameValuePair(k, v)));
        try {
            return new URIBuilder(new URI(url))
                    .addParameters(nvps)
                    .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static HttpClientResponseHandler<String> buildResponseHandler() {
        return response -> {
            int status = response.getCode();
            if (status >= 200 && status < 300) {
                return EntityUtils.toString(response.getEntity());
            } else {
                throw new IOException("Unexpected response status: " + status);
            }
        };
    }

    private static HttpClientConnectionManager getHttpClientConnectionManager() throws NoSuchAlgorithmException,
            KeyStoreException,
            KeyManagementException {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(3000L))
                .build();
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(10)
                .setDefaultConnectionConfig(connectionConfig)
                .setSSLSocketFactory(getSslConnectionSocketFactory())
                .build();
    }

    private static SSLConnectionSocketFactory getSslConnectionSocketFactory() throws NoSuchAlgorithmException,
            KeyStoreException,
            KeyManagementException {
        TrustStrategy acceptingTrustStrategy = (x509Certificates, s) -> true;
        SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null, acceptingTrustStrategy).build();
        return new SSLConnectionSocketFactory(sslContext, new NoopHostnameVerifier());
    }

    /**
     * register service into consul, body contains tcp health check.
     * returns true when http 2xx.
     */
    public Boolean register(String consulUrl,
                            String instanceId,
                            String name,
                            List<String> tags,
                            String address,
                            Integer port,
                            Map<String, String> meta) {
        return register(consulUrl, instanceId, name, tags, address, port, meta, null);
    }

    public Boolean register(String consulUrl,
                            String instanceId,
                            String name,
                            List<String> tags,
                            String address,
                            Integer port,
                            Map<String, String> meta,
                            Map<String, String> headers) {
        String body = handleRequestBody(instanceId, name, tags, address, port, meta);
        Map<String, String> uriParams = new HashMap<>();
        uriParams.put("replace-existing-check", "true");
        String result = doPut(consulUrl, uriParams, body, headers);
        log.info("consul register result, instanceId:{}, name:{}, address:{}, port:{}", instanceId, name, address, port);
        return Objects.nonNull(result);
    }

    /**
     * deregister service from consul by instance id.
     */
    public Boolean deregister(String consulUrl, String instanceId) {
        return deregister(consulUrl, instanceId, null);
    }

    public Boolean deregister(String consulUrl, String instanceId, Map<String, String> headers) {
        String url = consulUrl + "/v1/agent/service/deregister/" + instanceId;
        String result = doDelete(url, null, headers);
        log.info("consul deregister result, instanceId:{}", instanceId);
        return Objects.nonNull(result);
    }

    /**
     * query healthy instances of service, GET /v1/health/service/{name}?passing=true
     *
     * @return list of ConsumerConfig(service instance), empty when none
     */
    public List<ConsumerConfig> discovery(String consulUrl, String serviceName) {
        return discovery(consulUrl, serviceName, null);
    }

    public List<ConsumerConfig> discovery(String consulUrl, String serviceName, Map<String, String> headers) {
        List<ConsumerConfig> result = new ArrayList<>();
        Map<String, String> params = new HashMap<>();
        params.put("passing", "true");
        String json = doGet(consulUrl + "/v1/health/service/" + serviceName, params, headers);
        if(Objects.isNull(json) || json.length() < 1) {
            log.warn("consul discovery empty response, service:{}", serviceName);
            return result;
        }
        try {
            JsonNode array = JsonUtil.parseObject(json);
            if(Objects.nonNull(array) && array.isArray()) {
                for(JsonNode node : array) {
                    JsonNode serviceNode = node.get("Service");
                    if(Objects.isNull(serviceNode)) {
                        continue;
                    }
                    ConsumerConfig consumerConfig = new ConsumerConfig();
                    consumerConfig.setName(serviceNode.path("Service").asText(serviceName));
                    String address = serviceNode.path("Address").asText();
                    consumerConfig.setIp(address);
                    consumerConfig.setPort(serviceNode.path("Port").asInt());
                    if(log.isDebugEnabled()) {
                        log.debug("consul discovery instance, service:{}, address:{}, port:{}", serviceName, address, consumerConfig.getPort());
                    }
                    result.add(consumerConfig);
                }
            }
        } catch (Exception e) {
            log.warn("consul discovery parse exception, service:{}, json:{}", serviceName, json, e);
        }
        return result;
    }

    public String handleRequestBody(String instanceId,
                                     String name,
                                     List<String> tags,
                                     String address,
                                     Integer port,
                                     Map<String, String> meta) {
        String tcp = address.concat(":").concat(port.toString());
        String tagString = "";

        if(Objects.nonNull(tags) && tags.size() > 0) {
            for (String it: tags) {
                tagString = tagString.concat("\"")
                        .concat(it)
                        .concat("\", ");
            }
            tagString = tagString.substring(0, tagString.length() - 2);
        } else {
            tagString = "";
        }

        String metaString = "";
        if(Objects.nonNull(meta) && meta.size() > 0) {
            for(Map.Entry<String, String> entry: meta.entrySet()) {
                metaString = metaString.concat("\"")
                        .concat(entry.getKey())
                        .concat("\": \"")
                        .concat(entry.getValue())
                        .concat("\", ");
            }
            metaString = metaString.substring(0, metaString.length() - 2);
        } else {
            metaString = "";
        }

        String json = """
                {
                    "ID": "%s",
                    "Name": "%s",
                    "Tags": [%s],
                    "Address": "%s",
                    "Port": %d,
                    "Meta": {%s},
                    "EnableTagOverride": true,
                    "Check": {
                        "DeregisterCriticalServiceAfter": "10m",
                        "TCP": "%s",
                        "Interval": "10s",
                        "Timeout": "5s"
                    },
                  "Weights": {
                    "Passing": 3,
                    "Warning": 1
                  }
                }
                """;
        String result = String.format(json, instanceId, name, tagString, address, port, metaString, tcp);
        return result;
    }

}
