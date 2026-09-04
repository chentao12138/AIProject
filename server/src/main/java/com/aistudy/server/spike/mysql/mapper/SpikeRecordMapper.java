package com.aistudy.server.spike.mysql.mapper;

import com.aistudy.server.spike.mysql.SpikeRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * SPIKE-002 ONLY. BaseMapper for SpikeRecord.
 * Not a business mapper.
 */
@Mapper
public interface SpikeRecordMapper extends BaseMapper<SpikeRecord> {
}
