package com.consilens.cli.verify;

import com.consilens.sdk.verify.PolicyCheck;
import com.consilens.sdk.verify.Verdict;
import com.consilens.sdk.verify.VerifyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerifyReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRenderInconclusiveChecksAsSkippedWithReason() throws Exception {
        VerifyResult result = VerifyResult.builder()
                .verdict(Verdict.INCONCLUSIVE)
                .exitCode(3)
                .checks(List.of(
                        PolicyCheck.builder().name("anyDifference").passed(true).build(),
                        PolicyCheck.builder().name("maxNewDifferences").passed(false)
                                .actual(2L).limit(0L).build(),
                        PolicyCheck.builder().name("baseline").passed(null)
                                .message("failOn=new requires a baseline file").build()))
                .message("not evaluable: baseline: failOn=new requires a baseline file")
                .build();

        List<Path> written = VerifyReportWriter.write(result, List.of("junit"), tempDir);

        String xml = Files.readString(written.get(0), StandardCharsets.UTF_8);
        // 不可评估不能渲染成通过的 testcase：必须是 skipped 且带原因
        assertThat(xml).contains("<skipped message=\"failOn=new requires a baseline file\"/>");
        assertThat(xml).contains("failures=\"1\"");
        assertThat(xml).contains("skipped=\"1\"");
        assertThat(xml.split("<failure ", -1).length - 1).isEqualTo(1);
    }
}
