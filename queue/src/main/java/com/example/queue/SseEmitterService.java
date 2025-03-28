package com.example.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SseEmitterService {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 사용자별 SSE 연결 생성
    public SseEmitter createEmitter(String userId) {
        SseEmitter emitter = new SseEmitter(60_000L); // 60초 타임아웃
        emitters.put(userId, emitter);

        log.info("emitter 생성 확인: {}", emitters.get(userId));

        // 클라이언트 연결 종료 시 자동 삭제
        emitter.onCompletion(() -> emitters.remove(userId));
        emitter.onTimeout(() -> emitters.remove(userId));

        log.info("SSE 연결 생성: messageId={}", userId);
        return emitter;
    }

    // 특정 사용자에게 메시지 전송
    public void sendMessage(QueueDTO dto) {
        String userId = dto.recordIdValue();
        SseEmitter emitter = emitters.get(userId);

        if (emitter != null) {
            try {
                String message = objectMapper.writeValueAsString(dto);
                emitter.send(SseEmitter.event().name("queue").data(message));
                log.info("SSE 메시지 전송: userId={}, message={}", userId, message);
            } catch (Exception e) {
                log.error("SSE 전송 실패: userId={}, error={}", userId, e.getMessage());
                emitters.remove(userId); // 전송 실패 시 제거
            }
        } else {
            log.warn("SSEEmitter 없음: userId={}", userId);
        }
    }
}
