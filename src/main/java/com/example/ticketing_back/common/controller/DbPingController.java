package com.example.ticketing_back.common.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Health", description = "서버 상태 확인")
@RestController
@RequestMapping("/api")
public class DbPingController {

    private final JdbcTemplate jdbcTemplate;

    public DbPingController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Operation(summary = "DB 연결 확인", description = "DB에서 현재 DB 이름과 시간, 버전을 조회합니다.")
    @GetMapping("/db-ping")
    public Map<String, Object> dbPing() {
        return jdbcTemplate.queryForMap(
                "select database() as db, now() as db_time, version() as version");
    }
}