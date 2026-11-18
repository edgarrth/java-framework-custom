package pe.axiz.spring.scheduling.r2dbc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** R2DBC storage settings for scheduling. */
@ConfigurationProperties("axiz.scheduling")
public record AxizSchedulingR2dbcProperties(@DefaultValue("shedlock") String table) {}
