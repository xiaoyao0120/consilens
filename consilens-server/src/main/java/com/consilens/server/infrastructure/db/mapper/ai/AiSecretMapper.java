package com.consilens.server.infrastructure.db.mapper.ai;

import com.consilens.server.infrastructure.db.entity.ai.AiSecretEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AiSecretMapper {

    @Insert("INSERT INTO cs_ai_secret (secret_request_id, actor_id, session_id, draft_id, status, "
            + "cipher_algorithm, key_id, nonce, ciphertext, tag, read_count, max_reads, expires_at, version, "
            + "created_at, consumed_at) VALUES (#{secretRequestId}, #{actorId}, #{sessionId}, #{draftId}, "
            + "#{status}, #{cipherAlgorithm}, #{keyId}, #{nonce}, #{ciphertext}, #{tag}, #{readCount}, "
            + "#{maxReads}, #{expiresAt}, #{version}, #{createdAt}, #{consumedAt})")
    int insert(AiSecretEntity entity);

    @Select("SELECT * FROM cs_ai_secret WHERE secret_request_id = #{secretRequestId}")
    AiSecretEntity findById(@Param("secretRequestId") String secretRequestId);

    /**
     * Fulfillment CAS: PENDING -> PROVIDED only once.
     */
    @Update("UPDATE cs_ai_secret SET status = 'PROVIDED', cipher_algorithm = #{cipherAlgorithm}, "
            + "key_id = #{keyId}, nonce = #{nonce}, ciphertext = #{ciphertext}, tag = #{tag}, "
            + "version = version + 1 WHERE secret_request_id = #{secretRequestId} "
            + "AND actor_id = #{actorId} AND session_id = #{sessionId} AND status = 'PENDING' "
            + "AND (expires_at IS NULL OR expires_at > #{now})")
    int fulfill(@Param("secretRequestId") String secretRequestId,
                @Param("actorId") String actorId,
                @Param("sessionId") String sessionId,
                @Param("now") LocalDateTime now,
                @Param("cipherAlgorithm") String cipherAlgorithm,
                @Param("keyId") String keyId,
                @Param("nonce") byte[] nonce,
                @Param("ciphertext") byte[] ciphertext,
                @Param("tag") byte[] tag);

    /**
     * Read CAS: increments read_count only while within max_reads and unexpired.
     */
    @Update("UPDATE cs_ai_secret SET read_count = read_count + 1 "
            + "WHERE secret_request_id = #{secretRequestId} AND actor_id = #{actorId} "
            + "AND session_id = #{sessionId} AND status = 'PROVIDED' "
            + "AND (expires_at IS NULL OR expires_at > #{now}) AND read_count < max_reads")
    int incrementRead(@Param("secretRequestId") String secretRequestId,
                      @Param("actorId") String actorId,
                      @Param("sessionId") String sessionId,
                      @Param("now") LocalDateTime now);

    @Update("UPDATE cs_ai_secret SET status = 'CONSUMED', nonce = NULL, ciphertext = NULL, tag = NULL, "
            + "consumed_at = #{now} WHERE secret_request_id = #{secretRequestId} "
            + "AND actor_id = #{actorId} AND session_id = #{sessionId}")
    int consume(@Param("secretRequestId") String secretRequestId,
                @Param("actorId") String actorId,
                @Param("sessionId") String sessionId,
                @Param("now") LocalDateTime now);

    @Update("UPDATE cs_ai_secret SET status = 'REVOKED', nonce = NULL, ciphertext = NULL, tag = NULL, "
            + "consumed_at = #{now} WHERE secret_request_id = #{secretRequestId} "
            + "AND actor_id = #{actorId} AND session_id = #{sessionId}")
    int revoke(@Param("secretRequestId") String secretRequestId,
               @Param("actorId") String actorId,
               @Param("sessionId") String sessionId,
               @Param("now") LocalDateTime now);

    /**
     * Expires stale PENDING/PROVIDED requests and clears ciphertext.
     */
    @Update("UPDATE cs_ai_secret SET status = 'EXPIRED', nonce = NULL, ciphertext = NULL, tag = NULL, "
            + "consumed_at = #{now} WHERE status IN ('PENDING', 'PROVIDED') "
            + "AND expires_at IS NOT NULL AND expires_at <= #{now}")
    int expireStale(@Param("now") LocalDateTime now);

    @Delete("DELETE FROM cs_ai_secret WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
