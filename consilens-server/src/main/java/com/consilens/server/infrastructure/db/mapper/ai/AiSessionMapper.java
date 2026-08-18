package com.consilens.server.infrastructure.db.mapper.ai;

import com.consilens.server.infrastructure.db.entity.ai.AiSessionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AiSessionMapper {

    @Insert("INSERT INTO cs_ai_session (id, actor_id, request_id, title, objective, status, workflow_stage, "
            + "active_run_id, next_seq, snapshot_seq, version, lease_owner, lease_expires_at, created_at, updated_at) "
            + "VALUES (#{id}, #{actorId}, #{requestId}, #{title}, #{objective}, #{status}, #{workflowStage}, "
            + "#{activeRunId}, #{nextSeq}, #{snapshotSeq}, #{version}, #{leaseOwner}, #{leaseExpiresAt}, "
            + "#{createdAt}, #{updatedAt})")
    int insert(AiSessionEntity entity);

    @Select("SELECT * FROM cs_ai_session WHERE id = #{id}")
    AiSessionEntity findById(@Param("id") String id);

    @Select("SELECT * FROM cs_ai_session WHERE actor_id = #{actorId} AND request_id = #{requestId} LIMIT 1")
    AiSessionEntity findByActorAndRequestId(@Param("actorId") String actorId,
                                            @Param("requestId") String requestId);

    @Delete("DELETE FROM cs_ai_session WHERE id = #{id}")
    int deleteById(@Param("id") String id);

    /**
     * Optimistic-lock full update. Returns affected rows; callers require 1.
     */
    @Update("UPDATE cs_ai_session SET actor_id = #{entity.actorId}, request_id = #{entity.requestId}, "
            + "title = #{entity.title}, objective = #{entity.objective}, status = #{entity.status}, "
            + "workflow_stage = #{entity.workflowStage}, active_run_id = #{entity.activeRunId}, "
            + "next_seq = #{entity.nextSeq}, snapshot_seq = #{entity.snapshotSeq}, "
            + "version = #{entity.version}, lease_owner = #{entity.leaseOwner}, "
            + "lease_expires_at = #{entity.leaseExpiresAt}, updated_at = #{entity.updatedAt} "
            + "WHERE id = #{entity.id} AND version = #{expectedVersion}")
    int updateWithVersion(@Param("entity") AiSessionEntity entity,
                          @Param("expectedVersion") long expectedVersion);

    @Update("UPDATE cs_ai_session SET lease_owner = #{workerId}, lease_expires_at = #{expiresAt}, "
            + "version = version + 1, updated_at = #{now} "
            + "WHERE id = #{id} AND actor_id = #{actorId} "
            + "AND (lease_owner IS NULL OR lease_expires_at IS NULL OR lease_expires_at <= #{now} "
            + "OR lease_owner = #{workerId})")
    int tryAcquireLease(@Param("id") String id,
                        @Param("actorId") String actorId,
                        @Param("workerId") String workerId,
                        @Param("now") LocalDateTime now,
                        @Param("expiresAt") LocalDateTime expiresAt);

    @Update("UPDATE cs_ai_session SET lease_expires_at = #{expiresAt}, version = version + 1, "
            + "updated_at = #{now} WHERE id = #{id} AND lease_owner = #{workerId} AND version = #{expectedVersion}")
    int renewLease(@Param("id") String id,
                   @Param("workerId") String workerId,
                   @Param("expectedVersion") long expectedVersion,
                   @Param("now") LocalDateTime now,
                   @Param("expiresAt") LocalDateTime expiresAt);

    @Update("UPDATE cs_ai_session SET lease_owner = NULL, lease_expires_at = NULL, version = version + 1, "
            + "updated_at = #{now} WHERE id = #{id} AND lease_owner = #{workerId}")
    int releaseLease(@Param("id") String id,
                     @Param("workerId") String workerId,
                     @Param("now") LocalDateTime now);

    /**
     * Atomic seq reservation for event append. Returns 0 on conflict.
     */
    @Update("UPDATE cs_ai_session SET next_seq = next_seq + #{delta}, version = version + 1, "
            + "updated_at = #{now} WHERE id = #{sessionId} AND next_seq = #{expectedNextSeq}")
    int advanceNextSeq(@Param("sessionId") String sessionId,
                       @Param("expectedNextSeq") long expectedNextSeq,
                       @Param("delta") int delta,
                       @Param("now") LocalDateTime now);

    @Update("UPDATE cs_ai_session SET snapshot_seq = #{seq}, version = version + 1, updated_at = #{now} "
            + "WHERE id = #{sessionId} AND version = #{expectedVersion}")
    int advanceSnapshotSeq(@Param("sessionId") String sessionId,
                           @Param("seq") long seq,
                           @Param("expectedVersion") long expectedVersion,
                           @Param("now") LocalDateTime now);
}
