package com.evision.monitoring;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자용 수집 현황 API (명세 6.5).
 *
 * TODO(확인 필요): 운영자 접근 통제 방식이 정해지지 않았다 (명세 12장 미결 #5).
 * 현재는 경로만 /api/admin/**로 분리했다. 운영 서버는 보안 그룹에서 8080 포트를 열지 않았으므로
 * 외부에서는 접근할 수 없고, SSH 터널로만 조회한다.
 */
@RestController
@RequestMapping("/api/admin")
public class CollectionStatusController {

    private final CollectionStatusService service;

    public CollectionStatusController(CollectionStatusService service) {
        this.service = service;
    }

    /**
     * OP-08 수집 현황
     *
     * @param from 조회 시작 (예: 2026-09-27T00:00:00, KST)
     * @param to   조회 끝 (from보다 뒤, 최대 31일)
     */
    @GetMapping("/collection-status")
    public CollectionStatusResponse collectionStatus(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return service.getStatus(from, to);
    }
}
