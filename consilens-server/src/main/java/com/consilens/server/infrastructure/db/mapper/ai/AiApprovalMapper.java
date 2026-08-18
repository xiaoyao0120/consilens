package com.consilens.server.infrastructure.db.mapper.ai;

import com.consilens.server.infrastructure.db.entity.ai.AiApprovalEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AiApprovalMapper {

    @Insert("INSERT INTO cs_ai_approval (approval_id, session_id, proposed_run_id, status, action_digest, "
            + "safe_summary, safe_actions_json, actor_id, decided_at, expires_at, version) "
            + "VALUES (#{approvalId}, #{sessionId}, #{proposedRunId}, #{status}, #{actionDigest}, "
            + "#{safeSummary}, #{safeActionsJson}, #{actorId}, #{decidedAt}, #{expiresAt}, #{version})")
    int insert(AiApprovalEntity entity);

    @Select("SELECT * FROM cs_ai_approval WHERE approval_id = #{approvalId}")
    AiApprovalEntity findById(@Param("approvalId") String approvalId);

    /**
     * CAS decision: only one PENDING + version can ever win.
     */
    @Update("UPDATE cs_ai_approval SET status = #{status}, actor_id = #{actorId}, decided_at = #{now}, "
            + "version = version + 1 WHERE approval_id = #{approvalId} AND status = 'PENDING' "
            + "AND version = #{expectedVersion}")
    int decide(@Param("approvalId") String approvalId,
               @Param("actorId") String actorId,
               @Param("expectedVersion") long expectedVersion,
               @Param("status") String status,
               @Param("now") LocalDateTime now);

    @Delete("DELETE FROM cs_ai_approval WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
