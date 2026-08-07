package com.example.homeserver.Controller;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.example.homeserver.Video;
import com.example.homeserver.Service.VideoService;

@Controller
@RequestMapping("/videos")
public class VideoController {

    
    @Autowired
    private VideoService videoService;

    
 // 動画一覧表示用のエンドポイントを追加
    @GetMapping("")
    public String getAllVideos(Model model) {
        List<Video> videos = videoService.getAllVideos();
        System.out.println("get all videos: " + videos);
        model.addAttribute("videos", videos);
        
        
        return "video/list";
    }
    @GetMapping("/play/{id}")
    public ResponseEntity<Resource> playVideo(
            @PathVariable Long id,
            @RequestHeader HttpHeaders headers
    ) throws IOException {


        Resource resource = videoService.getVideo(id);


        if(resource == null) {
            return ResponseEntity.notFound().build();
        }


        long fileLength = resource.contentLength();


        return ResponseEntity.ok()
                .header(
                    HttpHeaders.ACCEPT_RANGES,
                    "bytes"
                )
                .contentType(
                    MediaType.parseMediaType("video/mp4")
                )
                .contentLength(fileLength)
                .body(resource);
    }
    
    @GetMapping("/view/{id}")
    public String viewVideo(
            @PathVariable Long id,
            Model model) {

        Video video = videoService.getVideoById(id);

        if(video == null) {
            return "redirect:/videos";
        }

        model.addAttribute("video", video);

        return "video/play";
    }
    
    @GetMapping("/upload")
    public String uploadPage() {
        return "video/upload";
    }
    
    @PostMapping("/upload")
    public String upload(
            @RequestParam("file") MultipartFile file
    ) {

        videoService.upload(file);

        return "redirect:/videos";
    }
    
    @GetMapping("/delete/{id}")
    public String deleteVideo(@PathVariable Long id) {

        videoService.deleteVideo(id);

        return "redirect:/videos";
    }
    
    @GetMapping("/videos/edit/{id}")
    public String editVideo(
            @PathVariable Long id,
            Model model){

        Video video =
            videoService.getVideoById(id);

        model.addAttribute("video", video);

        return "video-edit";
    }


    @PostMapping("/edit")
    public String editVideo(
            @RequestParam Long id,
            @RequestParam String title) {


        Video video =
                videoService.getVideoById(id);


        if(video != null){

            video.setTitle(title);

            videoService.saveVideo(video);

        }


        return "redirect:/videos";

    }

}
