package com.example.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmitterService {

    private Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private Map<String, QueueDTO> records = new ConcurrentHashMap<>();
    private Map<String, Long> messageTimestamps = new ConcurrentHashMap<>();
    private ObjectMapper objectMapper = new ObjectMapper();

    private final RedisTemplate<String, String> redisTemplate;

    // 사용자별 SSE 연결 생성
    public SseEmitter createEmitter(String userId, String messageId) {
        messageTimestamps.put(userId, extractTimeStamp(messageId)); // Stream 메세지 ID 저장

        SseEmitter emitter = new SseEmitter(60_000L); // 60초 타임아웃
        emitters.put(userId, emitter); // SSE Emitter 저장

        // 클라이언트 연결 종료 시 자동 삭제
        emitter.onCompletion(() -> emitters.remove(userId));
        emitter.onTimeout(() -> emitters.remove(userId));

        // 이미 수신돼서 대기중인 메세지가 있으면(거의 100프로 있다 봐야 할듯...?)
        if (records.containsKey(userId)) {
//            log.info("이미 대기중인 메세지 존재: {}", userId);

            try {
                String response = objectMapper.writeValueAsString(records.get(userId));
                emitter.send(SseEmitter.event().name("queue").data(response));
//                log.info("SSE 메시지 전송: userId={}, message={}", userId, response);

                // SSE 연결 종료 책임은 클라이언트에게
            } catch (Exception e) {
//                log.error("SSE 전송 실패: userId={}, error={}", userId, e.getMessage());
                emitter.onCompletion(() -> emitters.remove(userId)); // 전송 실패 시 제거
            }
        }

        return emitter;
    }

    public void sendMessage(MapRecord<String, Object, Object> message) {
        String messageId = message.getId().getValue();
        String userId = (String) message.getValue().get("userId");
//        log.info("메세지 발신자 식별값: {}", userId);

        // 아직 emitter가 생성되지 않음(메세지가 emitter 생성보다 먼저 도착)
        if (!emitters.containsKey(userId)) {
            QueueDTO dto = new QueueDTO(userId, 0);
            records.put(userId, dto);
        }

        // emitter가 생성되어 있다면
        emitters.forEach((clientUserId, emitter) -> {
//            long clientTime = messageTimestamps.getOrDefault(clientUserId, 1798761599000L);
            long clientTime = messageTimestamps.getOrDefault(clientUserId, 99999L);
            double processPercent = 100 * Math.pow(Math.log10(10 * ((double) extractTimeStamp(messageId) / clientTime)), 20);
            processPercent = Math.floor(processPercent * 100) / 100.000;

            QueueDTO dto = new QueueDTO(clientUserId, processPercent);

            if (emitter != null) {
                try {
                    log.info("emitter 보유 사용자 및 진행률: {}, {}", clientUserId, processPercent);
                    String response = objectMapper.writeValueAsString(dto);
                    emitter.send(SseEmitter.event().name("queue").data(response));
                    // SSE 연결 종료 책임은 클라이언트에게
                } catch (Exception e) {
                    emitters.remove(clientUserId); // 전송 실패 시 제거
                }
            } else {
                records.put(clientUserId, dto);
            }
        });
    }

    private long extractTimeStamp(String messageId) {
        Pattern pattern = Pattern.compile("^(\\d+)-");
        Matcher matcher = pattern.matcher(messageId); // 타임스탬프 부분
        if (matcher.find()) {
            String timestamp = matcher.group(1).substring(8);  // 임의 서브스트링
            return Long.parseLong(timestamp);
        }

        return 0L;
    }
}

