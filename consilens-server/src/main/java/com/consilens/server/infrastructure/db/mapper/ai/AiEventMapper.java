package com.consilens.server.infrastructure.db.mapper.ai;

import com.consilens.server.infrastructure.db.entity.ai.AiEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiEventMapper {

    @Insert("INSERT INTO cs_ai_event (event_id, session_id, seq, run_id, turn_id, event_type, visibility, "
            + "schema_version, payload, created_at) VALUES (#{eventId}, #{sessionId}, #{seq}, #{runId}, "
            + "#{turnId}, #{eventType}, #{visibility}, #{schemaVersion}, #{payload}, #{createdAt})")
    int insert(AiEventEntity entity);

    @Select("SELECT * FROM cs_ai_event WHERE session_id = #{sessionId} AND seq > #{afterSeq} "
            + "ORDER BY seq ASC LIMIT #{limit}")
    List<AiEventEntity> listAfter(@Param("sessionId") String sessionId,
                                  @Param("afterSeq") long afterSeq,
                                  @Param("limit") int limit);

    @Delete("DELETE FROM cs_ai_event WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
