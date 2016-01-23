package com.fesskiev.player.model;


import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MusicFolder {

    public List<File> musicFiles;
    public List<File> folderImages;
    public List<MusicFile> musicFilesDescription;
    public String folderName;

    public MusicFolder() {
        musicFiles = new ArrayList<>();
        folderImages = new ArrayList<>();
        musicFilesDescription = new ArrayList<>();
    }

    @Override
    public String toString() {
        return "MusicFolder{" +
                "musicFiles=" + getMusicFilesString() +
                ", folderName='" + folderName + '\'' +
                ", folderImage='" + getFolderImageFilesString() + '\'' +
                '}';
    }

    public String getMusicFilesString() {
        StringBuilder sb = new StringBuilder();
        for (File file : musicFiles) {
            sb.append(file.getName());
            sb.append("\n");
        }
        return sb.toString();
    }

    public String getFolderImageFilesString() {
        StringBuilder sb = new StringBuilder();
        for (File file : folderImages) {
            sb.append(file.getName());
            sb.append("\n");
        }
        return sb.toString();
    }
}
