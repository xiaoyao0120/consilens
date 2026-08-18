package com.consilens.server.infrastructure.db.mapper.ai;

import com.consilens.server.infrastructure.db.entity.ai.AiToolCallEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiToolCallMapper {

    @Insert("INSERT INTO cs_ai_tool_call (call_id, session_id, run_id, turn_id, source_order, tool_name, status, "
            + "risk_level, redacted_args, args_digest, idempotency_key, action_digest, result_event_seq, "
            + "error_code, retryable, result_summary, started_at, ended_at, version) "
            + "VALUES (#{callId}, #{sessionId}, #{runId}, #{turnId}, #{sourceOrder}, #{toolName}, #{status}, "
            + "#{riskLevel}, #{redactedArgs}, #{argsDigest}, #{idempotencyKey}, #{actionDigest}, "
            + "#{resultEventSeq}, #{errorCode}, #{retryable}, #{resultSummary}, #{startedAt}, #{endedAt}, #{version})")
    int insert(AiToolCallEntity entity);

    @Select("SELECT * FROM cs_ai_tool_call WHERE call_id = #{callId}")
    AiToolCallEntity findById(@Param("callId") String callId);

    @Select("SELECT * FROM cs_ai_tool_call WHERE session_id = #{sessionId} AND idempotency_key = #{idempotencyKey} "
            + "AND status = 'SUCCEEDED' LIMIT 1")
    AiToolCallEntity findSucceededByIdempotencyKey(@Param("sessionId") String sessionId,
                                                   @Param("idempotencyKey") String idempotencyKey);

    @Update("UPDATE cs_ai_tool_call SET session_id = #{entity.sessionId}, run_id = #{entity.runId}, "
            + "turn_id = #{entity.turnId}, source_order = #{entity.sourceOrder}, tool_name = #{entity.toolName}, "
            + "status = #{entity.status}, risk_level = #{entity.riskLevel}, "
            + "redacted_args = #{entity.redactedArgs}, args_digest = #{entity.argsDigest}, "
            + "idempotency_key = #{entity.idempotencyKey}, action_digest = #{entity.actionDigest}, "
            + "result_event_seq = #{entity.resultEventSeq}, error_code = #{entity.errorCode}, "
            + "retryable = #{entity.retryable}, result_summary = #{entity.resultSummary}, "
            + "started_at = #{entity.startedAt}, ended_at = #{entity.endedAt}, version = #{entity.version} "
            + "WHERE call_id = #{entity.callId} AND version = #{expectedVersion}")
    int updateWithVersion(@Param("entity") AiToolCallEntity entity,
                          @Param("expectedVersion") long expectedVersion);

    @Delete("DELETE FROM cs_ai_tool_call WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
