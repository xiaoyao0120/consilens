package com.consilens.conncetor.base;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.enums.DatabaseFeature;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseSqlQueryGeneratorTest {

    private final BaseSqlQueryGenerator generator = new BaseSqlQueryGenerator(new TestCapabilityProvider()) {
    };

    @Test
    void shouldCountMissingRowsInFullJoinFallback() {
        String sql = generator.getJoinDiffStatsSQL(
                "s1", "source_table", "s", List.of("id"), List.of("value"), null,
                "s2", "target_table", "t", List.of("id"), List.of("value"), null);

        assertTrue(sql.contains("SUM(CASE WHEN (t.\"id\" IS NULL) THEN 1 ELSE 0 END) AS target_missing"));
        assertTrue(sql.contains("SUM(CASE WHEN (s.\"id\" IS NULL) THEN 1 ELSE 0 END) AS source_missing"));
        assertTrue(sql.contains("WHERE (s.\"id\" IS NULL)"));
    }

    private static class TestCapabilityProvider implements CapabilityProvider {
        @Override
        public boolean supportsFeature(DatabaseFeature feature) {
            return false;
        }

        @Override
        public Set<DatabaseFeature> getSupportedFeatures() {
            return Collections.emptySet();
        }

        @Override
        public String getDefaultSchema() {
            return "public";
        }

        @Override
        public String getCatalogSeparator() {
            return ".";
        }

        @Override
        public String getPaginationHint(long offset, long limit) {
            return "";
        }

        @Override
        public char getWildcardEscapeChar() {
            return '\\';
        }

        @Override
        public String escapePattern(String pattern) {
            return pattern;
        }

        @Override
        public String getOpenQuote() {
            return "\"";
        }

        @Override
        public String getCloseQuote() {
            return "\"";
        }
    }
}
