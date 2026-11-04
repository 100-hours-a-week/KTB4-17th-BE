package com.team.dating_backend.notification.support;

import static org.assertj.core.api.Assertions.assertThat;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

public final class NotificationKafkaFaultProxy implements AutoCloseable {

    private static final int BROKERS = 3;
    private static final int FIRST_PROXY_PORT = 8666;
    private final ToxiproxyContainer container;
    private final Map<Channel, List<Route>> routes = new EnumMap<>(Channel.class);
    private ToxiproxyClient client;

    public enum Channel {
        PRODUCER(19092), CONSUMER(19093);

        private final int brokerPort;

        Channel(int brokerPort) {
            this.brokerPort = brokerPort;
        }
    }

    public NotificationKafkaFaultProxy(Network network) {
        container = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0").withNetwork(network);
        for (var channel : Channel.values()) {
            routes.put(channel, new ArrayList<>());
        }
    }

    public void start() {
        container.start();
        client = new ToxiproxyClient(container.getHost(), container.getControlPort());
    }

    public void registerBroker(KafkaContainer broker, String alias, int index) throws IOException {
        assertThat(index).isBetween(1, BROKERS);
        for (var channel : Channel.values()) {
            assertThat(routes.get(channel)).hasSize(index - 1);
            int port = FIRST_PROXY_PORT + channel.ordinal() * BROKERS + index - 1;
            String address = container.getHost() + ":" + container.getMappedPort(port);
            String upstream = alias + ":" + channel.brokerPort;
            var proxy = client.createProxy(channel.name().toLowerCase(java.util.Locale.ROOT) + "-" + index,
                "0.0.0.0:" + port, upstream);
            routes.get(channel).add(new Route(address, proxy));
            broker.withListener(upstream, () -> address);
        }
    }

    public String bootstrapServers(Channel channel) {
        assertThat(routes.get(channel)).hasSize(BROKERS);
        return routes.get(channel).stream().map(Route::address).collect(Collectors.joining(","));
    }

    public void setEnabled(Channel channel, boolean enabled) throws IOException {
        assertThat(routes.get(channel)).hasSize(BROKERS);
        IOException failure = null;
        for (var route : routes.get(channel)) {
            try {
                if (enabled) {
                    route.proxy().enable();
                } else {
                    route.proxy().disable();
                }
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public List<Map<String, Object>> description() {
        return routes.entrySet().stream().flatMap(entry -> entry.getValue().stream()
            .map(route -> Map.<String, Object>of("channel", entry.getKey().name(), "address", route.address(),
                "upstream", route.proxy().getUpstream(), "enabled", route.proxy().isEnabled())))
            .toList();
    }

    @Override
    public void close() {
        container.close();
    }

    private record Route(String address, Proxy proxy) {}
}
