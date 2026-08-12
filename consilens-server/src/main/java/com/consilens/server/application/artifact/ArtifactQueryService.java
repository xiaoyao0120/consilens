package com.consilens.server.application.artifact;

import com.consilens.server.api.dto.ArtifactListDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.domain.enums.ArtifactKind;

import java.time.Instant;
import java.util.Collection;

public interface ArtifactQueryService {

    PageResponse<ArtifactListDto> listArtifacts(int page,
                                                int pageSize,
                                                Collection<ArtifactKind> kinds,
                                                String keyword,
                                                Instant startTime,
                                                Instant endTime,
                                                String traceId);
}
