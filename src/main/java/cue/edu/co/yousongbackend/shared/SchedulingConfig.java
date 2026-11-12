package cue.edu.co.yousongbackend.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} methods (the scheduled close of sessions, {@code sesion.CierrePorHorario}). */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
