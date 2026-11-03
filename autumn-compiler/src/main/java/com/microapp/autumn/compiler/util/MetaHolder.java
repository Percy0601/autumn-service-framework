package com.microapp.autumn.compiler.util;

import com.microapp.autumn.compiler.model.ExportEntry;
import com.microapp.autumn.compiler.model.ReferenceEntry;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * compile-time metadata holder, shared between processors and freemarker templates.
 */
@Slf4j
public class MetaHolder {
    private static Integer serverPort = 8761;
    private static ConcurrentHashMap<String, ExportEntry> exportEntries = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, ReferenceEntry> referenceServices = new ConcurrentHashMap<>();

    private MetaHolder() {

    }

    public static void setServerPort(Integer _serverPort) {
        serverPort = _serverPort;
    }

    public static Integer getServerPort() {
        return serverPort;
    }

    public static void addExportEntry(ExportEntry entry) {
        if (exportEntries.containsKey(entry.getImplementName())) {
            return;
        }
        exportEntries.put(entry.getImplementName(), entry);
    }

    public static List<ExportEntry> getExportEntries() {
        return new ArrayList<>(exportEntries.values());
    }

    public static void addReferService(String referService, ReferenceEntry referServiceEntry) {
        if (referenceServices.containsKey(referService)) {
            return;
        }
        referenceServices.put(referService, referServiceEntry);
    }

    public static ConcurrentHashMap<String, ReferenceEntry> getReferService() {
        return referenceServices;
    }

}
