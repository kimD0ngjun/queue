# 대기열 구현
---
## 아키텍처
<img width="608" alt="스크린샷 2025-03-31 오전 12 16 44" src="https://github.com/user-attachments/assets/1fe025bc-fa73-494e-bc97-7cdf600ea262" />

## 사용 스택
- `Spring Boot` : 트래픽 수용 서버와 대기열 처리 서버 구현, Redis Stream 메세지 수신 및 SSE 연결
- `Redis` : Redis Stream 기반 대기열 큐 활용
- `SSE` : 클라이언트 실시간 처리 정보 전달
- `JavaScript` : SSE 연결 및 대기열 처리 결과 제공
- `JMeter` : 대용량 트래픽 테스트 툴

## 구현 결과
<img width="48%" height="288" alt="로딩바" src="https://github.com/user-attachments/assets/914f009c-66bb-487e-8db6-3ac93351b18a" /><img width="48%" height="288" alt="로그" src="https://github.com/user-attachments/assets/81ffb0c2-f6b5-45ce-8908-7a41e0299a3f" />

## 트러블 슈팅

### 1) Redis Stream 메세지 수신과 SSE 연결 비정합성 해결

<img width="80%" alt="sse연결과레디스스트림수신의비정합성" src="https://github.com/user-attachments/assets/606b0567-b7e2-4ba3-a183-a079cd19b297" />

- 문제 발생 : Redis Stream 메세지 수신이 더 빨라서 SSE 연결 전에 메세지가 소실되는 문제 발생
- 문제 해결 : 클라이언트 식별값(`userId`)과 메세지 식별값(`RecordId`)을 매핑시키는 저장소 구축으로 메세지 저장 후, SSE 연결 시점에 메세지 확인될 시 전달하는 이벤트 커스텀 큐 구현
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmitterService {

    private Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private Map<String, QueueDTO> records = new ConcurrentHashMap<>();
    private Map<String, Long> messageTimestamps = new ConcurrentHashMap<>();

    // ... SSE 생성 & 연결 메소드

        SseEmitter emitter = new SseEmitter(60_000L); // 60초 타임아웃
        emitters.put(userId, emitter); // SSE Emitter 저장

        // ...

        if (records.containsKey(userId)) {
            try {
                String response = objectMapper.writeValueAsString(records.get(userId));
                emitter.send(SseEmitter.event().name("queue").data(response)); // 이미 수신돼서 대기중인 메세지가 있으면 송신

                // ...

        return emitter;
    }

    // ... Redis Stream 메세지 수신 및 클라이언트 전달 메소드

        if (!emitters.containsKey(userId)) {
            QueueDTO dto = new QueueDTO(userId, 0);
            records.put(userId, dto); // 임시 응답 저장
        }

        // emitter가 생성되어 있다면
        emitters.forEach((clientUserId, emitter) -> {

        // ...
```

### 2) 실시간 데이터 처리 퍼센트 환산

<img width="80%" alt="redis_stream_메세지들" src="https://github.com/user-attachments/assets/d07c2081-2edd-4802-b467-2724de586acd" />

- 문제 발생 : 밀리세컨드 타임스탬프 기반으로 변화량을 계산하기 때문에 실제 변화량은 매우 미미한 편
- 문제 해결 : 로그를 적용하여 분수 변화량의 비율 폭을 늘이고 고수치 제곱근을 통해 각 변화폭 수치를 극단적으로 늘이면서 해결
```java
long clientTime = messageTimestamps.getOrDefault(clientUserId, 99999L); // 통상 타임스탬프 변화는 인덱스 8 이후부터 증가폭 이 늘어남
double processPercent = 100 * Math.pow(Math.log10(10 * ((double) extractTimeStamp(messageId) / clientTime)), 20); // 정규화 + 로그 + 제곱근 처리
processPercent = Math.floor(processPercent * 100) / 100.000; // 소수 둘째 자리까지 표현
```
