package com.meetingautomation.api.session;

import java.util.List;

/** Safe semantic Review failure containing only stable field paths and codes. */
public final class ReviewValidationException extends RuntimeException {
    private final List<ReviewValidationIssue> issues;

    public ReviewValidationException(List<ReviewValidationIssue> issues) {
        super("Review confirmation requirements are incomplete.");
        if (issues == null || issues.isEmpty()) throw new IllegalArgumentException("Review issues are required.");
        this.issues = List.copyOf(issues);
    }

    public List<ReviewValidationIssue> issues() { return issues; }
}
