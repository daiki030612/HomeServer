package com.example.homeserver.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.homeserver.Service.FolderService;

@Controller
public class FolderController {

    @Autowired
    private FolderService folderService;


    // フォルダ作成
    @PostMapping("/folders/create")
    public String createFolder(
            @RequestParam String name,
            @RequestParam(required = false) Long parentId) {


        folderService.createFolder(
                name,
                parentId
        );


        return "redirect:/videos";

    }

}