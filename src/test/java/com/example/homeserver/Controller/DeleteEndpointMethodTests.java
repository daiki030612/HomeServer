package com.example.homeserver.Controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Service.FolderService;
import com.example.homeserver.Service.TagService;
import com.example.homeserver.Service.VideoService;

class DeleteEndpointMethodTests {

    @Test
    void videoDeletionAcceptsPostAndRejectsGet() throws Exception {
        VideoService videoService = mock(VideoService.class);
        VideoController controller = new VideoController();
        ReflectionTestUtils.setField(controller, "videoService", videoService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/videos/delete/10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/videos"));
        mockMvc.perform(get("/videos/delete/10"))
                .andExpect(status().isMethodNotAllowed());

        verify(videoService).deleteVideo(10L);
    }

    @Test
    void tagDeletionAcceptsPostAndRejectsGet() throws Exception {
        TagService tagService = mock(TagService.class);
        when(tagService.deleteTag(20L)).thenReturn(true);
        VideoController controller = new VideoController();
        ReflectionTestUtils.setField(controller, "tagService", tagService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/videos/tag/delete/20"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/videos"));
        mockMvc.perform(get("/videos/tag/delete/20"))
                .andExpect(status().isMethodNotAllowed());

        verify(tagService).deleteTag(20L);
    }

    @Test
    void folderDeletionAcceptsPostAndRejectsGet() throws Exception {
        FolderService folderService = mock(FolderService.class);
        Folder folder = new Folder();
        when(folderService.getFolderById(30L)).thenReturn(folder);
        when(folderService.deleteFolder(30L)).thenReturn(true);
        FolderController controller = new FolderController();
        ReflectionTestUtils.setField(controller, "folderService", folderService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/folders/delete/30"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/videos"));
        mockMvc.perform(get("/folders/delete/30"))
                .andExpect(status().isMethodNotAllowed());

        verify(folderService).deleteFolder(30L);
    }
}
