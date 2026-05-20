package com.consilens.server.infrastructure.db.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

final class DbTimeSupport {

    private DbTimeSupport() {
    }

    static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    static Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.atZone(ZoneId.systemDefault()).toInstant();
    }
}
