package com.example.homeserver.Controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.example.homeserver.Config.SecurityConfig;
import com.example.homeserver.Service.StorageInfo;
import com.example.homeserver.Service.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = StorageController.class, properties = "shared-auth.secret=test-only-shared-auth-secret-32-characters-minimum")
@Import(SecurityConfig.class)
class StorageControllerTests {
    @Autowired MockMvc mvc;
    @MockBean StorageService storage;

    @Test void anonymousRequestIsRejected() throws Exception { mvc.perform(get("/api/storage")).andExpect(status().is3xxRedirection()); }
    @Test void authenticatedRequestReturnsCapacityWithoutPaths() throws Exception {
        when(storage.current()).thenReturn(new StorageInfo(1000, 600, 400, 60.0, false, true));
        mvc.perform(get("/api/storage").with(user("viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalBytes").value(1000))
                .andExpect(jsonPath("$.usedBytes").value(600)).andExpect(jsonPath("$.freeBytes").value(400))
                .andExpect(jsonPath("$.usagePercent").value(60.0)).andExpect(jsonPath("$.warning").value(false))
                .andExpect(jsonPath("$.path").doesNotExist());
    }
}
