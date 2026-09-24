package com.aistudy.server.mastery.mapper;

import com.aistudy.server.mastery.entity.MasteryCalibrationConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MasteryCalibrationConfigMapper extends BaseMapper<MasteryCalibrationConfig> {

    @Select("SELECT * FROM mastery_calibration_config WHERE active = 1 LIMIT 1")
    MasteryCalibrationConfig selectActive();

    @Select("SELECT * FROM mastery_calibration_config WHERE version = #{version} LIMIT 1")
    MasteryCalibrationConfig selectByVersion(String version);
}
