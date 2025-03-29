package com.example.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmitterService {

    private static final String STREAM_KEY = "queue";
    private static final String CONSUMER_GROUP = "consumers";
    private static final String CONSUMER_NAME = "netty";

    private Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private Map<String, MapRecord<String, Object, Object>> records = new ConcurrentHashMap<>();
    private ObjectMapper objectMapper = new ObjectMapper();

    private final RedisTemplate<String, String> redisTemplate;

    // 사용자별 SSE 연결 생성
    public SseEmitter createEmitter(String userId) {
        SseEmitter emitter = new SseEmitter(60_000L); // 60초 타임아웃
        emitters.put(userId, emitter);

        log.info("emitter 생성 확인: {}", emitters.get(userId));
        emitters.forEach((k, v) -> log.info("Emitter 키: {}, 값: {}", k, v));

        // 클라이언트 연결 종료 시 자동 삭제
        emitter.onCompletion(() -> emitters.remove(userId));
        emitter.onTimeout(() -> emitters.remove(userId));

        log.info("SSE 연결 생성: userId={}", userId);

        // 이미 수신돼서 대기중인 메세지가 있으면
        if (records.containsKey(userId)) {
            sendMessage(records.get(userId));
        }

        return emitter;
    }

    // 특정 사용자에게 메시지 전송
    public void sendMessage(MapRecord<String, Object, Object> message) {
        String userId = (String) message.getValue().get("userId");
        log.info("메세지 발신자 식별값: {}", userId);

        int percent = getProgressPercentage(userId);
        log.info("현재 포지션: {}", percent);

        redisTemplate.opsForStream().acknowledge(CONSUMER_GROUP, message); // Ack 처리

        SseEmitter emitter = emitters.get(userId);
        QueueDTO dto = new QueueDTO(userId, percent);

        if (emitter != null) {
            try {
                String response = objectMapper.writeValueAsString(dto);
                emitter.send(SseEmitter.event().name("queue").data(response));
                log.info("SSE 메시지 전송: userId={}, message={}", userId, response);

                // SSE 연결 종료 책임은 클라이언트에게
            } catch (Exception e) {
                log.error("SSE 전송 실패: userId={}, error={}", userId, e.getMessage());
                emitters.remove(userId); // 전송 실패 시 제거
            }
        } else {
            log.warn("SSEEmitter 없음, 메시지 저장: userId={}", userId);
            records.put(userId, message);

            // 일정 시간 후 메시지를 삭제하는 작업 추가 (예: 10초 후)
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(10_000L);  // 10초 대기
                    if (records.containsKey(userId)) {
                        records.remove(userId);
                        log.warn("SSE 연결 안 됨, 메시지 삭제: userId={}", userId);
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }
    }

    private int getProgressPercentage(String userId) {
        // temp
        PendingMessages pendingMessages = redisTemplate.opsForStream()
                .pending(STREAM_KEY, Consumer.from(CONSUMER_GROUP, CONSUMER_NAME), Range.unbounded(), 10000L);

        long pendingCount = pendingMessages.stream().count(); // 소비는 됐으나 자신을 포함한 미처리 메세지 갯수 세림
        log.info("대기 메시지 개수: {}", pendingCount);

        int position = 0;
        boolean find = false;
        for (PendingMessage pendingMessage : pendingMessages) {
            String messageId = pendingMessage.getId().getValue();
            log.info("대기 메세지 식별값: {}", messageId);

            // 메시지의 ID로부터 실제 메시지를 조회
            List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
                    .range(STREAM_KEY, Range.closed(messageId, messageId));

            assert messages != null;
            if (!messages.isEmpty()) {
                MapRecord<String, Object, Object> messageValue = messages.getFirst();
                log.info("메세지 내용: {}", messageValue);
                String messageUserid = (String) messageValue.getValue().get("userId");
                log.info("클라이언트용 식별값(사용자 ID): {}", messageUserid);

                if (userId.equals(messageUserid)) {
                    find = true;
                    break;
                }
            }

            position++;
        }

        log.info("찾았나?: {}", find);
        position = find ? position : (int) pendingCount;
        return pendingCount == 0 ? 100 : (int) ((1 - (position / (double) pendingCount)) * 100);
    }

//    private void closeSseConnection(String userId) {
//        SseEmitter emitter = emitters.get(userId);
//        if (emitter != null) {
//            try {
//                emitter.complete(); // SSE 연결을 종료
//                emitters.remove(userId); // emitters 제거
//            } catch (Exception e) {
//                log.error("SSE 연결 종료 실패: userId={}, error={}", userId, e.getMessage());
//            }
//        }
//    }
}
