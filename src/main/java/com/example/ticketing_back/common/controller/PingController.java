package com.example.ticketing_back.common.controller;

import com.example.ticketing_back.common.dto.PingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.ZonedDateTime;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Health", description = "서버 상태 확인")
@RestController
@RequestMapping("/api")
public class PingController {

    private final Environment env;

    public PingController(Environment env) {
        this.env = env;
    }

    @Operation(summary = "서버 핑", description = "서버 응답 여부, 활성 프로파일, 서버 시간을 반환합니다.")
    @GetMapping("/ping")
    public PingResponse ping() {
        String[] profiles = env.getActiveProfiles();
        String profile = profiles.length == 0 ? "default" : String.join(",", profiles);
        return new PingResponse("OK", profile, ZonedDateTime.now().toString());
    }
}
