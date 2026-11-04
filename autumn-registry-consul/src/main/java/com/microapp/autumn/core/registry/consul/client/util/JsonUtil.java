package com.microapp.autumn.core.registry.consul.client.util;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

@Deprecated(since = "1.0.0", forRemoval = true)
@Slf4j
public class JsonUtil {
    private static ObjectMapper objectMapper = new ObjectMapper();
    private static final String STANDARD_FORMAT = "yyyy-MM-dd HH:mm:ss";
    static {
        objectMapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.setDateFormat(new SimpleDateFormat(STANDARD_FORMAT));
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private JsonUtil() {

    }

    public static <T> T parseObject(String json, Class<T> object) {
        T t = null;
        try {
            t = objectMapper.readValue(json, object);
        } catch (JsonProcessingException e) {
            log.error("json parse object exception: {}", e.getMessage());
        }
        return t;
    }

    public static <T> T parseObject(File file, Class<T> object) {
        T t = null;
        try {
            t = objectMapper.readValue(file, object);
        } catch (IOException e) {
            log.error("read file json parse exception: {}", e.getMessage());
        }
        return t;
    }

    public static <T> T parseArray(String jsonArray, TypeReference<T> reference) {
        T t = null;
        try {
            t = objectMapper.readValue(jsonArray, reference);
        } catch (JsonProcessingException e) {
            log.error("json-array parse list or map exception: {}", e.getMessage());
        }
        return t;
    }

    public static String json(Object object) {
        String jsonString = null;
        try {
            jsonString = objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("object parse json string exception: {}", e.getMessage());
        }
        return jsonString;
    }

    public static byte[] byteArray(Object object) {
        byte[] bytes = null;
        try {
            bytes = objectMapper.writeValueAsBytes(object);
        } catch (JsonProcessingException e) {
            log.error("object parse byte-array exception:{}", e.getMessage());
        }
        return bytes;
    }

    public static JsonNode parseObject(String jsonString) {
        JsonNode jsonNode = null;
        try {
            jsonNode = objectMapper.readTree(jsonString);
        } catch (JsonProcessingException e) {
            log.error("JSONString转为JsonNode失败：{}", e.getMessage());
        }
        return jsonNode;
    }

    public static JsonNode parseObject(Object object) {
        JsonNode jsonNode = objectMapper.valueToTree(object);
        return jsonNode;
    }

    public static String jsonString(JsonNode jsonNode) {
        String jsonString = null;
        try {
            jsonString = objectMapper.writeValueAsString(jsonNode);
        } catch (JsonProcessingException e) {
            log.error("json-node parse json-string exception: {}", e.getMessage());
        }
        return jsonString;
    }

    public static ObjectNode newJsonObject() {
        return objectMapper.createObjectNode();
    }

    public static ArrayNode jsonArray() {
        return objectMapper.createArrayNode();
    }

    public static String getString(JsonNode jsonObject, String key) {
        String s = jsonObject.get(key).asText();
        return s;
    }

    public static Integer getInteger(JsonNode jsonObject, String key) {
        Integer i = jsonObject.get(key).asInt();
        return i;
    }

    public static Boolean getBoolean(JsonNode jsonObject, String key) {
        Boolean bool = jsonObject.get(key).asBoolean();
        return bool;
    }

    public static JsonNode getJSONObject(JsonNode jsonObject, String key) {
        JsonNode json = jsonObject.get(key);
        return json;
    }
}
