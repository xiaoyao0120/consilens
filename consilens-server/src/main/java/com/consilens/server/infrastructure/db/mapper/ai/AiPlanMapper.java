package com.consilens.server.infrastructure.db.mapper.ai;

import com.consilens.server.infrastructure.db.entity.ai.AiPlanEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiPlanMapper {

    @Insert("INSERT INTO cs_ai_plan (plan_id, session_id, objective_id, status, plan_digest, "
            + "config_template_digest, safe_summary, plan_json, version, created_at) "
            + "VALUES (#{planId}, #{sessionId}, #{objectiveId}, #{status}, #{planDigest}, "
            + "#{configTemplateDigest}, #{safeSummary}, #{planJson}, #{version}, #{createdAt})")
    int insert(AiPlanEntity entity);

    @Select("SELECT * FROM cs_ai_plan WHERE plan_id = #{planId}")
    AiPlanEntity findById(@Param("planId") String planId);

    /**
     * CAS transition for claiming an approved plan (APPROVED -> EXECUTING).
     */
    @Update("UPDATE cs_ai_plan SET status = #{status}, version = version + 1 "
            + "WHERE plan_id = #{planId} AND status = 'APPROVED' AND version = #{expectedVersion}")
    int claimWithVersion(@Param("planId") String planId,
                         @Param("expectedVersion") long expectedVersion,
                         @Param("status") String status);

    @Update("UPDATE cs_ai_plan SET status = 'APPROVED', version = version + 1 "
            + "WHERE plan_id = #{planId} AND status = 'PREPARED' AND version = #{expectedVersion}")
    int approve(@Param("planId") String planId, @Param("expectedVersion") long expectedVersion);

    /**
     * Plain version bump used as the CAS guard for action completion.
     */
    @Update("UPDATE cs_ai_plan SET version = version + 1 WHERE plan_id = #{planId} "
            + "AND version = #{expectedVersion}")
    int bumpVersion(@Param("planId") String planId, @Param("expectedVersion") long expectedVersion);

    @Delete("DELETE FROM cs_ai_plan WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
