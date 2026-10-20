package com.meetingautomation.email.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailMarkdownRendererTests {
    private final EmailMarkdownRenderer renderer = new EmailMarkdownRenderer();

    @Test
    void mapsMeetingMarkdownStructureToEscapedReadableHtml() {
        String html = renderer.render("# 주간 회의\n\n## 결정 사항\n\n- **배포 일정** 확정\n- 담당: `민수`\n\n일정 <확인> 필요");

        assertThat(html).contains("<h1>주간 회의</h1>", "<h2>결정 사항</h2>", "<ul>", "<strong>배포 일정</strong>",
                "<code>민수</code>", "일정 &lt;확인&gt; 필요", "</ul>");
        assertThat(html).doesNotContain("<확인>", "# 주간 회의", "**배포 일정**");
    }
}
