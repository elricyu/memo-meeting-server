package com.memomeeting;

import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.*;
import java.nio.file.*;

@Component
public class AudioFiles {
    private final Path root;
    public AudioFiles(Settings settings) throws IOException {
        root=Path.of(settings.dataDir(),"audio"); Files.createDirectories(root);
    }
    public Path path(String id) {
        if (!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("无效的记录 ID");
        return root.resolve(id+".wav");
    }
    public RandomAccessFile open(String id) throws IOException {
        var file=new RandomAccessFile(path(id).toFile(),"rw");
        if(file.length()<44) { file.setLength(44); header(file); }
        file.seek(file.length()); return file;
    }
    public long bytes(String id) throws IOException { return Files.exists(path(id)) ? Math.max(0, Files.size(path(id))-44) : 0; }
    public void finalizeFile(String id) throws IOException {
        if(Files.exists(path(id))) try(var f=new RandomAccessFile(path(id).toFile(),"rw")){header(f);}
    }
    public void checkpoint(RandomAccessFile f) throws IOException { long pos=f.getFilePointer();header(f);f.seek(pos);f.getFD().sync(); }
    /** Preserve original audio, then replace only explicitly reviewed device-control intervals. */
    public void excludeRanges(String id,java.util.List<Store.Segment> segments) throws IOException {
        Path source=path(id);if(!Files.exists(source))return;
        Path original=root.resolve(id+".before-device-exclusions.wav");
        if(!Files.exists(original))Files.copy(source,original);
        Path temporary=Files.createTempFile(root,".filtered-",".wav");
        try {
            Files.copy(source,temporary,StandardCopyOption.REPLACE_EXISTING);
            try(var file=new RandomAccessFile(temporary.toFile(),"rw")){
                byte[] silence=new byte[4096];
                for(var segment:segments){
                    long start=Math.max(44,44+segment.startMs()*32),end=Math.min(file.length(),44+segment.endMs()*32);
                    file.seek(start);while(start<end){int size=(int)Math.min(silence.length,end-start);file.write(silence,0,size);start+=size;}
                }
                file.getFD().sync();
            }
            Files.move(temporary,source,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temporary);}
    }
    private void header(RandomAccessFile file) throws IOException {
        int size=(int)(file.length()-44);
        var h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(size+36).put("WAVEfmt ".getBytes()).putInt(16)
            .putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2)
            .putShort((short)16).put("data".getBytes()).putInt(size);
        file.seek(0);file.write(h.array());
    }
}
