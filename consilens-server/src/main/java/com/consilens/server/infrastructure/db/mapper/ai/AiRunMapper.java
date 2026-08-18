package com.consilens.server.infrastructure.db.mapper.ai;

import com.consilens.server.infrastructure.db.entity.ai.AiRunEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiRunMapper {

    @Insert("INSERT INTO cs_ai_run (run_id, session_id, request_id, resume_from_run_id, status, "
            + "lease_owner, lease_expires_at, turn_count, "
            + "tool_call_count, token_count, error_code, started_at, ended_at, version) "
            + "VALUES (#{runId}, #{sessionId}, #{requestId}, #{resumeFromRunId}, #{status}, "
            + "#{leaseOwner}, #{leaseExpiresAt}, #{turnCount}, "
            + "#{toolCallCount}, #{tokenCount}, #{errorCode}, #{startedAt}, #{endedAt}, #{version})")
    int insert(AiRunEntity entity);

    @Select("SELECT * FROM cs_ai_run WHERE run_id = #{runId}")
    AiRunEntity findById(@Param("runId") String runId);

    @Select("SELECT * FROM cs_ai_run WHERE session_id = #{sessionId} AND request_id = #{requestId} LIMIT 1")
    AiRunEntity findByRequestId(@Param("sessionId") String sessionId,
                                @Param("requestId") String requestId);

    @Update("UPDATE cs_ai_run SET session_id = #{entity.sessionId}, request_id = #{entity.requestId}, "
            + "resume_from_run_id = #{entity.resumeFromRunId}, status = #{entity.status}, "
            + "lease_owner = #{entity.leaseOwner}, lease_expires_at = #{entity.leaseExpiresAt}, "
            + "turn_count = #{entity.turnCount}, tool_call_count = #{entity.toolCallCount}, "
            + "token_count = #{entity.tokenCount}, error_code = #{entity.errorCode}, "
            + "started_at = #{entity.startedAt}, ended_at = #{entity.endedAt}, version = #{entity.version} "
            + "WHERE run_id = #{entity.runId} AND version = #{expectedVersion}")
    int updateWithVersion(@Param("entity") AiRunEntity entity,
                          @Param("expectedVersion") long expectedVersion);

    /**
     * Candidates for claim: QUEUED runs whose run lease is free or expired,
     * oldest first (their session lease is checked by the scheduler).
     */
    @Select("SELECT * FROM cs_ai_run WHERE status = 'QUEUED' "
            + "AND (lease_owner IS NULL OR lease_expires_at IS NULL OR lease_expires_at <= #{now}) "
            + "ORDER BY started_at ASC LIMIT #{limit}")
    List<AiRunEntity> listQueuedWithFreeLease(@Param("now") LocalDateTime now,
                                              @Param("limit") int limit);

    /**
     * Atomic claim: QUEUED -> RUNNING with owner, only when the lease is free.
     */
    @Update("UPDATE cs_ai_run SET status = 'RUNNING', lease_owner = #{workerId}, "
            + "lease_expires_at = #{expiresAt}, version = version + 1 "
            + "WHERE run_id = #{runId} AND status = 'QUEUED' "
            + "AND (lease_owner IS NULL OR lease_expires_at IS NULL OR lease_expires_at <= #{now})")
    int claimRun(@Param("runId") String runId,
                 @Param("workerId") String workerId,
                 @Param("now") LocalDateTime now,
                 @Param("expiresAt") LocalDateTime expiresAt);

    @Update("UPDATE cs_ai_run SET status = 'QUEUED', lease_owner = NULL, lease_expires_at = NULL, "
            + "version = version + 1 WHERE run_id = #{runId} AND lease_owner = #{workerId}")
    int releaseClaim(@Param("runId") String runId, @Param("workerId") String workerId);

    @Update("UPDATE cs_ai_run SET lease_expires_at = #{expiresAt}, version = version + 1 "
            + "WHERE run_id = #{runId} AND lease_owner = #{workerId} AND version = #{expectedVersion}")
    int renewLease(@Param("runId") String runId,
                   @Param("workerId") String workerId,
                   @Param("expectedVersion") long expectedVersion,
                   @Param("expiresAt") LocalDateTime expiresAt);

    @Update("UPDATE cs_ai_run SET lease_owner = NULL, lease_expires_at = NULL, version = version + 1 "
            + "WHERE run_id = #{runId} AND lease_owner = #{workerId}")
    int releaseRun(@Param("runId") String runId, @Param("workerId") String workerId);

    /**
     * Runs still RUNNING after their lease expired (crashed worker).
     */
    @Select("SELECT * FROM cs_ai_run WHERE status = 'RUNNING' AND lease_owner IS NOT NULL "
            + "AND lease_expires_at IS NOT NULL AND lease_expires_at <= #{now} "
            + "ORDER BY started_at ASC LIMIT #{limit}")
    List<AiRunEntity> listExpiredRunning(@Param("now") LocalDateTime now,
                                         @Param("limit") int limit);

    @Update("UPDATE cs_ai_run SET status = #{newStatus}, version = version + 1 "
            + "WHERE run_id = #{runId} AND status = #{expectedStatus}")
    int transitionStatus(@Param("runId") String runId,
                         @Param("expectedStatus") String expectedStatus,
                         @Param("newStatus") String newStatus);

    @Delete("DELETE FROM cs_ai_run WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
