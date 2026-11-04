package com.team.dating_backend.profile.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.ClientResources;
import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileCacheRedisProperties.class)
public class ProfileCacheRedisConfiguration {

    @Bean
    public LettuceConnectionFactory profileCacheRedisConnectionFactory(
        ProfileCacheRedisProperties properties, ClientResources clientResources) {
        RedisURI uri = redisUri(properties.getUrl());
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        server.setDatabase(uri.getDatabase());
        String userInfo = URI.create(properties.getUrl()).getUserInfo();
        if (userInfo != null) {
            int separator = userInfo.indexOf(':');
            if (separator >= 0) {
                server.setUsername(userInfo.substring(0, separator));
                server.setPassword(userInfo.substring(separator + 1));
            } else {
                server.setPassword(userInfo);
            }
        }
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
            .apply(uri)
            .clientResources(clientResources)
            .commandTimeout(properties.getCommandTimeout())
            .clientOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(properties.getConnectTimeout()).build())
                .timeoutOptions(TimeoutOptions.enabled())
                .build())
            .build();
        return new LettuceConnectionFactory(server, client);
    }

    @Bean
    public StringRedisTemplate profileCacheStringRedisTemplate(
        @Qualifier("profileCacheRedisConnectionFactory") LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    private static RedisURI redisUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!("redis".equals(uri.getScheme()) || "rediss".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return RedisURI.create(uri);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Profile cache Redis URL must be a valid redis:// or rediss:// URL");
        }
    }
}
