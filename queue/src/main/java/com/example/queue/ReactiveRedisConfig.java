package com.example.queue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;

@Configuration
@EnableAutoConfiguration(exclude={RedisAutoConfiguration.class, RedisReactiveAutoConfiguration.class}) // 자동 생성 설정과 커스텀 설정 충돌 방지
public class ReactiveRedisConfig {

    private static final String STREAM_KEY = "queue";
    private static final String CONSUMER_GROUP = "consumers";
    private static final String CONSUMER_NAME = "netty";

    @Value("${spring.data.redis.host}")
    private String host;
    @Value("${spring.data.redis.port}")
    private int port;
    @Value("${spring.data.redis.password}")
    private String password;

    @Bean
    // Lettuce는 ReactiveRedisConnectionFactory의 구현체 중 일부
    public ReactiveRedisConnectionFactory connectionFactory() {
        RedisStandaloneConfiguration redisConfiguration = new RedisStandaloneConfiguration();
        redisConfiguration.setHostName(host);
        redisConfiguration.setPort(port);
        redisConfiguration.setPassword(password);
        redisConfiguration.setDatabase(0);

        final SocketOptions socketoptions = SocketOptions.builder().connectTimeout(Duration.ofSeconds(10)).build();
        final ClientOptions clientoptions = ClientOptions.builder().socketOptions(socketoptions).build();

        LettuceClientConfiguration lettuceClientConfiguration = LettuceClientConfiguration.builder()
                .clientOptions(clientoptions)
                .commandTimeout(Duration.ofMinutes(1))
                .shutdownTimeout(Duration.ZERO)
                .build();

        return new LettuceConnectionFactory(redisConfiguration, lettuceClientConfiguration);
    }

    @Bean(name = "redisTemplate")
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(ReactiveRedisConnectionFactory connectionFactory) {
        // Jackson Serializer 설정
        ObjectMapper objectMapper = new ObjectMapper()
                // 모르는 속성에 실패처리 안함
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // created_at, updated_at 등 시간에 관한 처리
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATE_KEYS_AS_TIMESTAMPS);

        Jackson2JsonRedisSerializer<String> serializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, String.class);

        // Redis 직렬화 컨텍스트 설정
        RedisSerializationContext<String, String> context = RedisSerializationContext
                .<String, String>newSerializationContext()
                .key(RedisSerializer.string())
                .value(serializer)
                .hashKey(RedisSerializer.string())
                .hashValue(serializer)
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }

    // 스트림 메세지 리스너 세팅
    @Bean(name = "listenerContainer")
    public StreamMessageListenerContainer<String, MapRecord<String, Object, Object>> streamMessageListenerContainer(ReactiveRedisConnectionFactory connectionFactory) {
        return StreamMessageListenerContainer.create(
                (RedisConnectionFactory) reactiveRedisTemplate(connectionFactory).getConnectionFactory(),
                StreamMessageListenerContainer
                        .StreamMessageListenerContainerOptions.builder()
                        .hashKeySerializer(new StringRedisSerializer())
                        .hashValueSerializer(new Jackson2JsonRedisSerializer<>(Object.class))
                        .pollTimeout(Duration.ofMillis(20))
                        .build()
        );
    }
}
