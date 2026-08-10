package com.example.homeserver.Repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.homeserver.Entity.Folder;

@Repository
public interface FolderRepository
        extends JpaRepository<Folder, Long> {

    List<Folder> findByParentIsNullOrderBySortOrderAsc();

    List<Folder> findByParentOrderBySortOrderAsc(Folder parent);

}