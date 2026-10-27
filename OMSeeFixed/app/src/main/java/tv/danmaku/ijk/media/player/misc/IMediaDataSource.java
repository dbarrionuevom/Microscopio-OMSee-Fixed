package tv.danmaku.ijk.media.player.misc;

import java.io.IOException;

public interface IMediaDataSource {
    int readAt(long position, byte[] buffer, int offset, int size) throws IOException;
    long getSize() throws IOException;
    void close() throws IOException;
}
