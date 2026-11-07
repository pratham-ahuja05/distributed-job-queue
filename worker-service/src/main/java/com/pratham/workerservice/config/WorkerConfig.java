package com.pratham.workerservice.config;

import com.pratham.workerservice.queue.QueueKeys;
import com.pratham.workerservice.service.ChaosListener;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfig {

    @Bean
    RedisMessageListenerContainer chaosListenerContainer(RedisConnectionFactory factory, ChaosListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(listener, new ChannelTopic(QueueKeys.CHAOS_CHANNEL));
        return container;
    }
}
