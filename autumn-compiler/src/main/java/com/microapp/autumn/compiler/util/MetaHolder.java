package com.microapp.autumn.compiler.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.microapp.autumn.compiler.model.ExportEntry;
import com.microapp.autumn.compiler.model.ReferenceEntry;

/**
 * 编译期元数据容器。
 *
 * <p>状态按 processor 实例隔离（每个 processor 持有一个 {@link State}），不再使用静态可变字段，
 * 避免同一 javac 进程内多模块/多编译单元复用时串数据；已移除无人使用的 serverPort 死字段。</p>
 */
public final class MetaHolder {

    private MetaHolder() {
    }

    public static final class State {

        private final Map<String, ExportEntry> exportEntries = new LinkedHashMap<>();
        private final Map<String, ReferenceEntry> referenceServices = new LinkedHashMap<>();

        public boolean containsExport(String implName) {
            return exportEntries.containsKey(implName);
        }

        public void addExportEntry(ExportEntry entry) {
            if (Objects.isNull(entry) || Objects.isNull(entry.getImplementName())) {
                return;
            }
            exportEntries.putIfAbsent(entry.getImplementName(), entry);
        }

        public List<ExportEntry> getExportEntries() {
            return new ArrayList<>(exportEntries.values());
        }

        public boolean containsReference(String ifaceName) {
            return referenceServices.containsKey(ifaceName);
        }

        public void addReferService(String ifaceName, ReferenceEntry entry) {
            if (Objects.isNull(ifaceName) || Objects.isNull(entry)) {
                return;
            }
            referenceServices.putIfAbsent(ifaceName, entry);
        }

        public Map<String, ReferenceEntry> getReferService() {
            return referenceServices;
        }
    }
}
