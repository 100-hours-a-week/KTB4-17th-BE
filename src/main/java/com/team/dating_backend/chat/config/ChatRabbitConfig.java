package com.team.dating_backend.chat.config;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatRabbitProperties.class)
public class ChatRabbitConfig {

    @Bean
    public DirectExchange chatUserEventsExchange(ChatRabbitProperties properties) {
        // 사용자별 라우팅을 유지하고 브로커 재시작 후에도 교환기가 남게 한다.
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    public ApplicationRunner initializeChatRabbitExchange(
        AmqpAdmin admin, DirectExchange chatUserEventsExchange) {
        // 메시지 발행 전에도 상태 검사에 앞서 교환기를 선언한다.
        return args -> admin.declareExchange(chatUserEventsExchange);
    }
}
