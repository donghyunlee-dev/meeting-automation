package com.meetingautomation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/__test/errors")
class ErrorContractProbeController {

    @PostMapping(value = "/validation", consumes = MediaType.APPLICATION_JSON_VALUE)
    void validation(@Valid @RequestBody ValidationRequest request) {
    }

    @GetMapping("/unexpected")
    void unexpected() {
        throw new IllegalStateException("internal-exception-secret-marker");
    }

    @GetMapping("/required-header")
    void requiredHeader(@RequestHeader("X-Required-Value") String value) {
    }

    @GetMapping("/type-mismatch")
    void integerParameter(@RequestParam int count) {
    }

    @GetMapping("/minimum")
    void minimum(@RequestParam @Min(1) int count) {
    }

    @GetMapping("/invalid-return")
    @Size(max = 8)
    String invalidReturn() {
        return "invalid-return-value-marker";
    }

    @PostMapping(value = "/json-only", consumes = MediaType.APPLICATION_JSON_VALUE)
    void jsonOnly(@RequestBody ValidationRequest request) {
    }

    @PostMapping("/post-only")
    void postOnly() {
    }

    record ValidationRequest(@Size(max = 8) String value) {
    }
}
