package com.consilens.server.infrastructure.db.mapper.ai;

import com.consilens.server.infrastructure.db.entity.ai.AiSnapshotEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiSnapshotMapper {

    @Insert("INSERT INTO cs_ai_snapshot (session_id, seq, working_state, conversation_summary, "
            + "schema_version, created_at) VALUES (#{sessionId}, #{seq}, #{workingState}, "
            + "#{conversationSummary}, #{schemaVersion}, #{createdAt})")
    int insert(AiSnapshotEntity entity);

    @Select("SELECT * FROM cs_ai_snapshot WHERE session_id = #{sessionId} ORDER BY seq DESC LIMIT 1")
    AiSnapshotEntity latest(@Param("sessionId") String sessionId);

    @Delete("DELETE FROM cs_ai_snapshot WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
