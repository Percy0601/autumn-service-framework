package com.microapp.autumn.compiler.util;

import com.microapp.autumn.compiler.model.ExportEntry;
import com.microapp.autumn.compiler.model.ReferenceEntry;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compile-time metadata holder, shared between a processor and its freemarker templates.
 *
 * <p>Instance scoped on purpose: each annotation processor owns one holder, so two
 * processors (or two compilation units in the same javac process) can never leak
 * entries into each other. The old static maps made {@code @Export}/{@code @Reference}
 * metadata survive across independent compilations.</p>
 */
@Slf4j
public class MetaHolder {

    private final ConcurrentHashMap<String, ExportEntry> exportEntries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReferenceEntry> referenceServices = new ConcurrentHashMap<>();

    public void addExportEntry(ExportEntry entry) {
        exportEntries.putIfAbsent(entry.getImplementName(), entry);
    }

    public List<ExportEntry> getExportEntries() {
        return new ArrayList<>(exportEntries.values());
    }

    public void addReferService(String referService, ReferenceEntry referServiceEntry) {
        referenceServices.putIfAbsent(referService, referServiceEntry);
    }

    public ConcurrentHashMap<String, ReferenceEntry> getReferService() {
        return referenceServices;
    }

}
