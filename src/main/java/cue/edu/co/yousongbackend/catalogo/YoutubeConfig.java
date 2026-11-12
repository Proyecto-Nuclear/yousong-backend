package cue.edu.co.yousongbackend.catalogo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({YoutubeProperties.class, SugerenciasProperties.class})
class YoutubeConfig {
}
