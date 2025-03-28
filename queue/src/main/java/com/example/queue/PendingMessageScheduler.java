package com.example.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingMessageScheduler {

    private static final String STREAM_KEY = "queue";
    private static final String CONSUMER_GROUP = "consumers";
    private static final String CONSUMER_NAME = "netty";

    private final RedisTemplate<String, String> redisTemplate;

    @Scheduled(fixedRate = 10000)
    public void processPendingMessages() {
        // 에러나 일시 연결 중단으로 대기 처리된 메세지들 처리
        PendingMessages pendingMessages = redisTemplate.opsForStream()
                .pending(STREAM_KEY, Consumer.from(CONSUMER_GROUP, CONSUMER_NAME), Range.unbounded(), 100L);

        for (PendingMessage pendingMessage : pendingMessages) {
            // 해당 메시지의 ID를 이용해 Stream에서 메시지 조회
            String messageId = pendingMessage.getId().getValue();
            List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
                    .range(STREAM_KEY, Range.closed(messageId, messageId));

            if (messages != null && !messages.isEmpty()) {
                // 메시지 처리
                MapRecord<String, Object, Object> message = messages.getFirst();
                log.info("Pending 수신 아이디: {}", message.getId());
                log.info("Pending 수신 메세지: {}", message.getValue());

                // 메시지 처리 후 ACK 처리
                redisTemplate.opsForStream().acknowledge(STREAM_KEY, CONSUMER_GROUP, message.getId());
                log.info("메시지 ACK 처리 완료: {}", message.getId());
            }
        }
    }
}
