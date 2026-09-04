package com.aistudy.server.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Registers MyBatis Mapper scanning for the SPIKE-002 verification mapper.
 *
 * Placed on the "it" profile so unit tests (which exclude DataSource +
 * MyBatis-Plus auto-config) do not attempt to instantiate Mapper beans.
 *
 * Not a business config: remove this class together with the SPIKE-002 code.
 */
@Configuration
@Profile("it")
@MapperScan("com.aistudy.server.spike.mysql.mapper")
public class SpikeMybatisConfig {
}
