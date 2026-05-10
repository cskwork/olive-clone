package com.olive.commerce.common.audit;

import net.logstash.logback.marker.Markers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LogbackAuditLogger implements AuditLogger {

    static final String AUDIT_LOGGER_NAME = "olive.audit";

    private static final Logger AUDIT_LOG = LoggerFactory.getLogger(AUDIT_LOGGER_NAME);

    @Override
    public void log(String event, Map<String, Object> attributes) {
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("audit event 이름은 비어있을 수 없습니다.");
        }
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("event", event);
        if (attributes != null) {
            entries.putAll(attributes);
        }
        Marker marker = Markers.appendEntries(entries);
        AUDIT_LOG.info(marker, event);
    }
}
