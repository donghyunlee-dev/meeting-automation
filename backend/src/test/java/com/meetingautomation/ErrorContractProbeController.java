package com.meetingautomation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    record ValidationRequest(@Size(max = 8) String value) {
    }
}
