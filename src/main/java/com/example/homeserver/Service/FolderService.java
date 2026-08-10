package com.example.homeserver.Service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Repository.FolderRepository;

@Service
public class FolderService {

    @Autowired
    private FolderRepository folderRepository;


    // フォルダ作成
    public Folder createFolder(String name, Long parentId) {

        Folder folder = new Folder();

        folder.setName(name);

        folder.setSortOrder(0);


        // 親フォルダがある場合
        if (parentId != null) {

            Folder parent =
                    folderRepository
                            .findById(parentId)
                            .orElse(null);

            folder.setParent(parent);

        }


        return folderRepository.save(folder);

    }


    // ルートフォルダ取得
    public List<Folder> getRootFolders() {

        return folderRepository
                .findByParentIsNullOrderBySortOrderAsc();

    }


    // 子フォルダ取得
    public List<Folder> getChildFolders(
            Long parentId) {

        Folder parent =
                folderRepository
                        .findById(parentId)
                        .orElse(null);

        if (parent == null) {

            return List.of();

        }

        return folderRepository
                .findByParentOrderBySortOrderAsc(parent);

    }


    // ID検索
    public Folder getFolderById(Long id) {

        return folderRepository
                .findById(id)
                .orElse(null);

    }

}