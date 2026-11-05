package com.microapp.autumn.core.devops;

import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author: baoxin.zhao
 * @date: 2024/9/30
 */
public class ContextHolder {

    private ContextHolder() {

    }

    private static ConcurrentHashMap<String, Date> clients = new ConcurrentHashMap<>();

    public static void addClient(String ip) {
        if(clients.contains(ip)) {
            return;
        }
        clients.put(ip, new Date());
    }

    public static void removeClient(String ip) {
        if(!clients.contains(ip)) {
            return;
        }
        clients.remove(ip);
    }

    public static ConcurrentHashMap<String, Date> getClients() {
        return clients;
    }
}
