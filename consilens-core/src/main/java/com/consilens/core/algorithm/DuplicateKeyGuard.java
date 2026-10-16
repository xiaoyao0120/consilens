package com.consilens.core.algorithm;

import com.consilens.connector.api.ConnectorException;
import com.consilens.core.segment.TableSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 唯一键守卫（严格档 validateUniqueKeys=true）。
 *
 * <p>按键比对的正确性依赖"键唯一"：键重复时 join 的 FULL OUTER JOIN 会 N×M 行倍增，
 * checksum 的归因会退化成"整键所有行 SM+TM"——两者都不是用户能直接消费的结论。
 * 因此严格档在比对开始前对两侧各做一次探针（{@link TableSegment} 的
 * {@code findDuplicateKeys}），命中即明确失败，并给出两侧的键值与行数。
 *
 * <p>需要按"多重集"语义比较（值相等的重复行互相抵消）时，把
 * {@code executionOptions.validateUniqueKeys} 设为 false。
 */
@Slf4j
final class DuplicateKeyGuard {

    private DuplicateKeyGuard() {
    }

    static void assertUnique(TableSegment table1, TableSegment table2) {
        List<String> sourceViolations = probe(table1);
        List<String> targetViolations = probe(table2);
        if (sourceViolations.isEmpty() && targetViolations.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder(
                "唯一键校验失败：按键比对要求键唯一（validateUniqueKeys=true），但存在重复键。");
        if (!sourceViolations.isEmpty()) {
            message.append("\n  source(").append(displayName(table1)).append("): ")
                    .append(String.join("; ", sourceViolations));
        }
        if (!targetViolations.isEmpty()) {
            message.append("\n  target(").append(displayName(table2)).append("): ")
                    .append(String.join("; ", targetViolations));
        }
        message.append("\n如需按多重集语义比较（值相等的重复行互相抵消、其余按序配对），"
                + "请设置 executionOptions.validateUniqueKeys=false。");
        throw new ConnectorException(message.toString());
    }

    private static List<String> probe(TableSegment table) {
        try {
            return table.getDatabase().findDuplicateKeys(table);
        } catch (Exception e) {
            log.warn("Duplicate-key probe failed for {}: {}", table.getTablePath(), e.getMessage());
            return List.of();
        }
    }

    private static String displayName(TableSegment table) {
        try {
            return table.getDisplayName();
        } catch (Exception e) {
            return String.valueOf(table.getTablePath());
        }
    }
}
