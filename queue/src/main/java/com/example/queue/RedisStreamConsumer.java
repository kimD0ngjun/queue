package com.example.queue;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamConsumer
        implements StreamListener<String, MapRecord<String, Object, Object>>, InitializingBean, DisposableBean {

    private static final String STREAM_KEY = "queue";
    private static final String CONSUMER_GROUP = "consumers";
    private static final String CONSUMER_NAME = "netty";

    private Subscription subscription;

    private final StreamMessageListenerContainer<String, MapRecord<String, Object, Object>> listenerContainer;
    private final RedisTemplate<String, String> redisTemplate;
    private final SseEmitterService sseEmitterService;

    @Override
    public void onMessage(MapRecord<String, Object, Object> message) {
        log.info("수신 아이디: {}", message.getId());
        log.info("수신 메세지: {}", message.getValue());

        String userId = (String) message.getValue().get("userId");
        log.info("메세지 발신자 식별값: {}", userId);

        // SSE를 통해 클라이언트에게 전달
        sseEmitterService.sendMessage(userId);

        // Ack 처리 (중복처리 방지)
        redisTemplate.opsForStream().acknowledge(CONSUMER_GROUP, message);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        // 컨슈머 그룹 세팅
        if (!isStreamConsumerGroupExist()) {
            log.info("컨슈머 그룹 생성");
            redisTemplate.opsForStream()
                    .createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);
        }

        // 중재자 세팅
        this.subscription = listenerContainer.receive(
                Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
                this
        );
//
//        // 5초 간격으로 정보 취득(블로킹 처리)
//        this.subscription.await(Duration.ofSeconds(5));

        // Redis Listen 시작
        this.listenerContainer.start();
    }

    @PreDestroy
    @Override
    public void destroy() {
        try {
            if (this.listenerContainer != null) {
                log.info("리스너 컨테이너 종료");
                this.listenerContainer.stop();
            }

            if (this.subscription != null) {
                log.info("중재자 종료");
                this.subscription.cancel();
            }
        } catch (Exception e) {
            log.error("Redis Stream Listener Container 종료 중 오류 발생: {}", e.getMessage());
        }
    }

    private boolean isStreamConsumerGroupExist() {
        // 소비자 그룹이 존재하는지 확인하는 로직
        return redisTemplate.opsForStream().groups(STREAM_KEY)
                .stream()
                .anyMatch(group -> group.groupName().equals(CONSUMER_GROUP));
    }
}
