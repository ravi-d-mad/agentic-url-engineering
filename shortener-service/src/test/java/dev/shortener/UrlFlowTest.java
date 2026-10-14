package dev.shortener;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** End-to-end API contract tests against in-memory H2 (same Flyway migrations as production). */
@SpringBootTest
@AutoConfigureMockMvc
class UrlFlowTest {
    @Autowired MockMvc mvc;

    private String create(String json) throws Exception {
        String body = mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/urls/")))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }

    @Test
    void createRedirectAndCountClicks() throws Exception {
        String code = create("{\"originalUrl\":\"https://example.com/docs\"}");
        mvc.perform(get("/" + code)).andExpect(status().isFound()).andExpect(header().string("Location", "https://example.com/docs"));
        mvc.perform(get("/" + code)).andExpect(status().isFound());
        mvc.perform(get("/api/v1/urls/" + code + "/analytics")).andExpect(status().isOk()).andExpect(jsonPath("$.clickCount").value(2));
    }

    @Test
    void rejectsNonHttpSchemes() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content("{\"originalUrl\":\"javascript:alert(1)\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsExpiryInThePast() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalUrl\":\"https://example.com\",\"expiresAt\":\"2000-01-01T00:00:00Z\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCodeIs404() throws Exception {
        mvc.perform(get("/zzzzzzz")).andExpect(status().isNotFound());
    }

    @Test
    void deactivatedLinkIsGone() throws Exception {
        String code = create("{\"originalUrl\":\"https://example.com/a\"}");
        mvc.perform(delete("/api/v1/urls/" + code)).andExpect(status().isNoContent());
        mvc.perform(get("/" + code)).andExpect(status().isGone());
    }
}
