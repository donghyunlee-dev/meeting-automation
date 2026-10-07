package com.meetingautomation;

import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;

@Validated
public class ConstraintViolationProbeService {

    public void validateValue(@Size(max = 8) String value) {
    }
}
