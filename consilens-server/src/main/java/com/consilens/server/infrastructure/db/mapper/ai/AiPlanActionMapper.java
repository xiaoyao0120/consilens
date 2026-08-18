package com.consilens.server.infrastructure.db.mapper.ai;

import com.consilens.server.infrastructure.db.entity.ai.AiPlanActionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AiPlanActionMapper {

    @Insert("INSERT INTO cs_ai_plan_action (plan_id, action_id, sequence, action_type, name, safe_args_json, "
            + "status, idempotency_key, input_digest, resource_type, resource_id, result_digest, error_code, "
            + "retryable, started_at, ended_at) VALUES (#{planId}, #{actionId}, #{sequence}, #{actionType}, "
            + "#{name}, #{safeArgsJson}, #{status}, #{idempotencyKey}, #{inputDigest}, #{resourceType}, "
            + "#{resourceId}, #{resultDigest}, #{errorCode}, #{retryable}, #{startedAt}, #{endedAt})")
    int insert(AiPlanActionEntity entity);

    @Select("SELECT * FROM cs_ai_plan_action WHERE plan_id = #{planId} ORDER BY sequence ASC")
    List<AiPlanActionEntity> listByPlanId(@Param("planId") String planId);

    @Update("UPDATE cs_ai_plan_action SET status = #{status}, resource_type = #{resourceType}, "
            + "resource_id = #{resourceId}, error_code = #{errorCode}, retryable = #{retryable}, "
            + "ended_at = #{endedAt} WHERE plan_id = #{planId} AND action_id = #{actionId}")
    int complete(@Param("planId") String planId,
                 @Param("actionId") String actionId,
                 @Param("status") String status,
                 @Param("resourceType") String resourceType,
                 @Param("resourceId") String resourceId,
                 @Param("errorCode") String errorCode,
                 @Param("retryable") Boolean retryable,
                 @Param("endedAt") java.time.LocalDateTime endedAt);

    @Delete("DELETE FROM cs_ai_plan_action WHERE plan_id IN "
            + "(SELECT plan_id FROM cs_ai_plan WHERE session_id = #{sessionId})")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
