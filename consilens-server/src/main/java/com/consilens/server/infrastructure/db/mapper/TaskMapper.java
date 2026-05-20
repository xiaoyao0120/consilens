package com.consilens.server.infrastructure.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consilens.server.infrastructure.db.entity.TaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {

    @Select("SELECT * FROM cs_task WHERE serial_no = #{serialNo} FOR UPDATE")
    TaskEntity lockBySerialNo(@Param("serialNo") String serialNo);
}
