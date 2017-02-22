package com.fesskiev.mediacenter.data.model;

import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;

import com.fesskiev.mediacenter.R;
import com.fesskiev.mediacenter.data.source.local.db.DatabaseHelper;
import com.fesskiev.mediacenter.utils.BitmapHelper;
import com.fesskiev.mediacenter.utils.CacheManager;
import com.fesskiev.mediacenter.utils.Utils;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;
import org.jaudiotagger.tag.TagOptionSingleton;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.id3.ID3v24Frames;
import org.jaudiotagger.tag.images.Artwork;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public class AudioFile implements MediaFile, Comparable<AudioFile> {

    public interface OnAudioTagListener {
        void onFetchCompleted(AudioFile audioFile);
    }

    private Context context;
    public String id;
    public File filePath;
    public String artist;
    public String title;
    public String album;
    public String genre;
    public String bitrate;
    public String sampleRate;
    public String artworkPath;
    public int trackNumber;
    public long length;
    public long size;
    public long timestamp;
    public boolean inPlayList;
    public boolean isSelected;
    private OnAudioTagListener listener;

    public AudioFile() {
        fillEmptyFields();
    }

    public AudioFile(Context context, File filePath, String folderId, OnAudioTagListener listener) {
        this.id = folderId;
        this.context = context;
        this.filePath = filePath;
        this.listener = listener;
        renameFileCorrect();
        getTrackInfo();
    }

    public AudioFile(Cursor cursor) {

        id = cursor.getString(cursor.getColumnIndex(DatabaseHelper.ID));
        filePath = new File(cursor.getString(cursor.getColumnIndex(DatabaseHelper.TRACK_PATH)));
        artist = cursor.getString(cursor.getColumnIndex(DatabaseHelper.TRACK_ARTIST));
        title = cursor.getString(cursor.getColumnIndex(DatabaseHelper.TRACK_TITLE));
        album = cursor.getString(cursor.getColumnIndex(DatabaseHelper.TRACK_ALBUM));
        genre = cursor.getString(cursor.getColumnIndex(DatabaseHelper.TRACK_GENRE));
        bitrate = cursor.getString(cursor.getColumnIndex(DatabaseHelper.TRACK_BITRATE));
        sampleRate = cursor.getString(cursor.getColumnIndex(DatabaseHelper.TRACK_SAMPLE_RATE));
        artworkPath = cursor.getString(cursor.getColumnIndex(DatabaseHelper.TRACK_COVER));
        trackNumber = cursor.getInt(cursor.getColumnIndex(DatabaseHelper.TRACK_NUMBER));
        inPlayList = cursor.getInt(cursor.getColumnIndex(DatabaseHelper.TRACK_IN_PLAY_LIST)) == 1;
        isSelected = cursor.getInt(cursor.getColumnIndex(DatabaseHelper.TRACK_SELECTED)) == 1;
        length = cursor.getLong(cursor.getColumnIndex(DatabaseHelper.TRACK_LENGTH));
        size = cursor.getLong(cursor.getColumnIndex(DatabaseHelper.TRACK_SIZE));
        timestamp = cursor.getLong(cursor.getColumnIndex(DatabaseHelper.TRACK_TIMESTAMP));

    }

    private void renameFileCorrect() {
        //TODO fix replace symbols folder
        File newPath = new File(filePath.getParent(), Utils.replaceSymbols(filePath.getName()));
        boolean rename = filePath.renameTo(newPath);
        if (rename) {
            filePath = newPath;
        }
    }

    private void saveArtwork(Tag tag) {
        List<Artwork> artworks = tag.getArtworkList();
        for (Artwork artwork : artworks) {
            byte[] imageRawData = artwork != null ? artwork.getBinaryData() : null;
            if (imageRawData != null) {
                try {
                    File path = File.createTempFile(UUID.randomUUID().toString(),
                            ".png", new File(CacheManager.IMAGES_AUDIO_CACHE_PATH));

                    BitmapHelper.getInstance().saveBitmap(artwork.getBinaryData(), path);

                    artworkPath = path.getAbsolutePath();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                break;
            }
        }
    }

    private void fillEmptyFields() {
        if (artist == null || TextUtils.isEmpty(artist)) {
            artist = context.getString(R.string.empty_music_file_artist);
        }
        if (title == null || TextUtils.isEmpty(title)) {
            title = context.getString(R.string.empty_music_file_title);
        }
        if (album == null || TextUtils.isEmpty(album)) {
            album = context.getString(R.string.empty_music_file_album);
        }
        if (genre == null || TextUtils.isEmpty(genre)) {
            genre = context.getString(R.string.empty_music_file_genre);
        }
    }

    private void getTrackInfo() {
        parseMetadataTagger();
        if (listener != null) {
            listener.onFetchCompleted(this);
        }
    }

    private void parseMetadataTagger() {

        size = filePath.length();
        timestamp = System.currentTimeMillis();

        try {
            TagOptionSingleton.getInstance().setAndroid(true);
            org.jaudiotagger.audio.AudioFile file = AudioFileIO.read(filePath);
            AudioHeader audioHeader = file.getAudioHeader();

            bitrate = audioHeader.getBitRate() + " kbps " + (audioHeader.isVariableBitRate() ? "(VBR)" : "(CBR)");
            sampleRate = audioHeader.getSampleRateAsNumber() + " Hz";
            length = audioHeader.getTrackLength();

            if (audioHeader.isLossless()) {
                parseLossless(file);
            } else {
                parseMP3(file);
            }

        } catch (CannotReadException | IOException | TagException
                | ReadOnlyFileException | InvalidAudioFrameException e) {
            e.printStackTrace();
        } finally {
            fillEmptyFields();
        }
    }


    private void parseMP3(org.jaudiotagger.audio.AudioFile file) {
        Tag tag = file.getTag();
        if (tag != null && tag.hasCommonFields()) {
            if (tag.hasField(ID3v24Frames.FRAME_ID_ARTIST)) {
                artist = tag.getFirst(ID3v24Frames.FRAME_ID_ARTIST);
            }
            if (tag.hasField(ID3v24Frames.FRAME_ID_TITLE)) {
                title = tag.getFirst(ID3v24Frames.FRAME_ID_TITLE);
            }
            if (tag.hasField(ID3v24Frames.FRAME_ID_ALBUM)) {
                album = tag.getFirst(ID3v24Frames.FRAME_ID_ALBUM);
            }
            if (tag.hasField(ID3v24Frames.FRAME_ID_GENRE)) {
                genre = tag.getFirst(ID3v24Frames.FRAME_ID_GENRE);
            }
            if (tag.hasField(ID3v24Frames.FRAME_ID_TRACK)) {
                String number = tag.getFirst(ID3v24Frames.FRAME_ID_TRACK);
                if (!number.equals("null") && !TextUtils.isEmpty(number)) {
                    trackNumber = Integer.valueOf(number);
                }
            }

            saveArtwork(tag);
        }

        fillEmptyFields();
    }

    private void parseLossless(org.jaudiotagger.audio.AudioFile file) {
        FlacTag flacTag = (FlacTag) file.getTag();
        if (flacTag != null && flacTag.hasCommonFields()) {

            title = flacTag.getFirst(FieldKey.TITLE);
            artist = flacTag.getFirst(FieldKey.ARTIST);
            album = flacTag.getFirst(FieldKey.ALBUM);
            genre = flacTag.getFirst(FieldKey.GENRE);
            String number = flacTag.getFirst(FieldKey.TRACK);
            if (!TextUtils.isEmpty(number)) {
                try {
                    trackNumber = Integer.valueOf(number);
                } catch (NumberFormatException e) {
                    trackNumber = 0;
                    e.printStackTrace();
                }
            }

            saveArtwork(flacTag);

            fillEmptyFields();
        }
    }


    @Override
    public MEDIA_TYPE getMediaType() {
        return MEDIA_TYPE.AUDIO;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getArtworkPath() {
        return artworkPath;
    }

    @Override
    public String getFileName() {
        return filePath.getName();
    }

    @Override
    public String getFilePath() {
        return filePath.getPath();
    }

    @Override
    public long getLength() {
        return length;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean exists() {
        return filePath.exists();
    }

    @Override
    public boolean isDownloaded() {
        return filePath.getAbsolutePath().startsWith(CacheManager.CHECK_DOWNLOADS_FOLDER_PATH);
    }

    @Override
    public int compareTo(AudioFile another) {
        if (this.trackNumber > another.trackNumber) {
            return 1;
        } else if (this.trackNumber < another.trackNumber) {
            return -1;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioFile audioFile = (AudioFile) o;

        if (!filePath.equals(audioFile.filePath)) return false;
        if (!artist.equals(audioFile.artist)) return false;
        return title.equals(audioFile.title);

    }

    @Override
    public int hashCode() {
        int result = filePath.hashCode();
        result = 31 * result + artist.hashCode();
        result = 31 * result + title.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "AudioFile{" +
                "id='" + id + '\'' +
                ", filePath=" + filePath +
                ", artist='" + artist + '\'' +
                ", title='" + title + '\'' +
                ", album='" + album + '\'' +
                ", genre='" + genre + '\'' +
                ", bitrate='" + bitrate + '\'' +
                ", sampleRate='" + sampleRate + '\'' +
                ", artworkPath='" + artworkPath + '\'' +
                ", trackNumber=" + trackNumber +
                ", length=" + length +
                ", size=" + size +
                ", timestamp=" + timestamp +
                ", inPlayList=" + inPlayList +
                ", isSelected=" + isSelected +
                '}';
    }
}
