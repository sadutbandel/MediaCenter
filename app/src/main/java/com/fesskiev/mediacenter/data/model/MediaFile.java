package com.fesskiev.mediacenter.data.model;


public interface MediaFile {

    String getId();

    MEDIA_TYPE getMediaType();

    String getTitle();

    String getFileName();

    String getFilePath();

    String getArtworkPath();

    long getLength();

    long getSize();

    long getTimestamp();

    boolean exists();

    boolean inPlayList();

    void setToPlayList(boolean inPlaylist);

}
