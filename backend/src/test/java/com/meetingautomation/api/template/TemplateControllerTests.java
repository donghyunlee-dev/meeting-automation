package com.meetingautomation.api.template;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.api.error.ApiExceptionHandler;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TemplateControllerTests {

    @Test
    void missingRequiredResourceReturnsSafeInternalErrorWithoutPartialList() throws Exception {
        TemplateCatalog catalog = new TemplateCatalog(resourceLoaderWithMissingProjectTemplate());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TemplateController(catalog))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/api/v1/templates"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.category").value("INTERNAL"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains("templates/project.md"));
                    assertFalse(body.contains("FileNotFoundException"));
                });
    }

    @Test
    void unreadableRequiredResourceUsesSafeExceptionWithoutRetainingCause() {
        TemplateCatalog catalog = new TemplateCatalog(resourceLoaderWithUnreadableDefaultTemplate());

        IllegalStateException failure = assertThrows(IllegalStateException.class, catalog::listTemplates);

        assertFalse(failure.getMessage().contains("templates/default.md"));
        org.junit.jupiter.api.Assertions.assertEquals(null, failure.getCause());
    }

    private static ResourceLoader resourceLoaderWithMissingProjectTemplate() {
        return resourceLoader(location -> location.endsWith("default.md")
                ? new ByteArrayResource("# default".getBytes())
                : new ClassPathResource("templates/missing-project.md"));
    }

    private static ResourceLoader resourceLoaderWithUnreadableDefaultTemplate() {
        return resourceLoader(location -> location.endsWith("default.md")
                ? new AbstractResource() {
                    @Override
                    public InputStream getInputStream() throws IOException {
                        throw new IOException("private file path marker");
                    }

                    @Override
                    public String getDescription() {
                        return "unreadable template test resource";
                    }

                    @Override
                    public String getFilename() {
                        return "default.md";
                    }
                }
                : new ByteArrayResource("# project".getBytes()));
    }

    private static ResourceLoader resourceLoader(java.util.function.Function<String, Resource> resources) {
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return resources.apply(location);
            }

            @Override
            public ClassLoader getClassLoader() {
                return TemplateControllerTests.class.getClassLoader();
            }
        };
    }
}
