package com.team.dating_backend.common.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.ClientResources;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataRedisProperties.class)
public class RedisConfiguration {

    @Bean
    @Primary
    public LettuceConnectionFactory redisConnectionFactory(
        DataRedisProperties properties,
        DataRedisConnectionDetails connectionDetails,
        ClientResources clientResources,
        ObjectProvider<LettuceClientConfigurationBuilderCustomizer> configurationCustomizers,
        ObjectProvider<LettuceClientOptionsBuilderCustomizer> optionsCustomizers) {
        Assert.state(connectionDetails.getSentinel() == null && connectionDetails.getCluster() == null
            && connectionDetails.getMasterReplica() == null, "Only standalone Redis is supported");
        DataRedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();
        Assert.state(standalone != null, "Standalone Redis connection details are required");
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
            standalone.getHost(), standalone.getPort());
        server.setDatabase(standalone.getDatabase());
        server.setUsername(connectionDetails.getUsername());
        server.setPassword(RedisPassword.of(connectionDetails.getPassword()));

        ClientOptions.Builder options = ClientOptions.builder().timeoutOptions(TimeoutOptions.enabled());
        if (properties.getConnectTimeout() != null) {
            options.socketOptions(SocketOptions.builder().connectTimeout(properties.getConnectTimeout()).build());
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
            .clientResources(clientResources);
        if (properties.getTimeout() != null) {
            client.commandTimeout(properties.getTimeout());
        }
        if (properties.getClientName() != null) {
            client.clientName(properties.getClientName());
        }
        if (properties.getLettuce().getShutdownTimeout() != null) {
            client.shutdownTimeout(properties.getLettuce().getShutdownTimeout());
        }
        var sslBundle = connectionDetails.getSslBundle();
        if (sslBundle != null) {
            client.useSsl();
            SslOptions.Builder ssl = SslOptions.builder()
                .keyManager(sslBundle.getManagers().getKeyManagerFactory())
                .trustManager(sslBundle.getManagers().getTrustManagerFactory());
            if (sslBundle.getOptions().getCiphers() != null) {
                ssl.cipherSuites(sslBundle.getOptions().getCiphers());
            }
            if (sslBundle.getOptions().getEnabledProtocols() != null) {
                ssl.protocols(sslBundle.getOptions().getEnabledProtocols());
            }
            options.sslOptions(ssl.build());
        }
        if (properties.getUrl() != null && properties.getUrl().startsWith("rediss://")) {
            client.useSsl();
        }
        optionsCustomizers.orderedStream().forEach(customizer -> customizer.customize(options));
        client.clientOptions(options.build());
        configurationCustomizers.orderedStream().forEach(customizer -> customizer.customize(client));
        return new LettuceConnectionFactory(server, client.build());
    }

    @Bean
    @Primary
    public StringRedisTemplate stringRedisTemplate(
        @Qualifier("redisConnectionFactory") LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
