package com.example.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmitterService {

    private static final String STREAM_KEY = "queue";
    private static final String CONSUMER_GROUP = "consumers";
    private static final String CONSUMER_NAME = "netty";

    private Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private Map<String, QueueDTO> records = new ConcurrentHashMap<>();
    private Map<String, String> messageIds = new ConcurrentHashMap<>();
    private ObjectMapper objectMapper = new ObjectMapper();

    private final RedisTemplate<String, String> redisTemplate;

    // 사용자별 SSE 연결 생성
    public SseEmitter createEmitter(String userId) {
        SseEmitter emitter = new SseEmitter(60_000L); // 60초 타임아웃
        emitters.put(userId, emitter);

        // 클라이언트 연결 종료 시 자동 삭제
        emitter.onCompletion(() -> emitters.remove(userId));
        emitter.onTimeout(() -> emitters.remove(userId));

        // 이미 수신돼서 대기중인 메세지가 있으면
        if (records.containsKey(userId)) {
            try {
                String response = objectMapper.writeValueAsString(records.get(userId));
                emitter.send(SseEmitter.event().name("queue").data(response));
                log.info("SSE 메시지 전송: userId={}, message={}", userId, response);

                // SSE 연결 종료 책임은 클라이언트에게
            } catch (Exception e) {
                log.error("SSE 전송 실패: userId={}, error={}", userId, e.getMessage());
                emitters.remove(userId); // 전송 실패 시 제거
            }
        }

        return emitter;
    }

    public void sendMessage(MapRecord<String, Object, Object> message) {
        String currentMessageId = message.getId().getValue();
        log.info("메세지 ID: {}", currentMessageId);

        String userId = (String) message.getValue().get("userId");
        log.info("메세지 발신자 식별값: {}", userId);

        emitters.forEach((clientUserId, emitter) -> {
            String userMessageId = getMessageId(clientUserId);
            long currentTimeStamp = extractTimeStamp(currentMessageId);
            long userTimeStamp = extractTimeStamp(userMessageId);

            int percent = (userTimeStamp != 0L) ? (int) (((double) currentTimeStamp / userTimeStamp) * 100) : 0;
            QueueDTO dto = new QueueDTO(clientUserId, percent);

            try {
                String response = objectMapper.writeValueAsString(dto);
                emitter.send(SseEmitter.event().name("queue").data(response));
                log.info("SSE 메시지 전송: userId={}, 진행률={}%", clientUserId, percent);
            } catch (IOException e) {
                log.error("SSE 전송 실패: userId={}, error={}", clientUserId, e.getMessage());
                emitters.remove(clientUserId); // 전송 실패한 경우 제거
            }
        });
    }

    private String getMessageId(String userId) {
        String userMessageId = messageIds.get(userId);
        if (userMessageId != null) {
            return userMessageId;
        }

        PendingMessages pendingMessages = redisTemplate.opsForStream()
                .pending(STREAM_KEY, Consumer.from(CONSUMER_GROUP, CONSUMER_NAME), Range.unbounded(), 10000L);

        PendingMessage userPendingMessage = pendingMessages.stream().filter(
                pendingMessage -> {
                    String messageId = pendingMessage.getIdAsString();
                    log.info("대기 메세지 식별값 ID: {}", messageId);

                    List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
                            .range(STREAM_KEY, Range.closed(messageId, messageId));

                    assert messages != null;
                    if (!messages.isEmpty()) {
                        MapRecord<String, Object, Object> messageValue = messages.getFirst();
                        log.info("메세지 내용: {}", messageValue);
                        String messageUserid = (String) messageValue.getValue().get("userId");
                        log.info("클라이언트용 식별값(사용자 ID): {}", messageUserid);

                        return userId.equals(messageUserid);
                    }

                    return false;
                }
        ).findFirst().orElse(null);

        assert userPendingMessage != null;
        userMessageId = userPendingMessage.getIdAsString();
        messageIds.put(userId, userMessageId);

        return userMessageId;
    }

    private long extractTimeStamp(String messageId) {
        Pattern pattern = Pattern.compile("^(\\d+)-");
        Matcher matcher = pattern.matcher(messageId);

        if (matcher.find()) {
            return Long.parseLong(matcher.group(1)); // 타임스탬프 부분
        }

        return 0L;
    }
}
