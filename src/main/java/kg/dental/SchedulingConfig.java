package kg.dental;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Включает @Scheduled (утренняя сводка). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
