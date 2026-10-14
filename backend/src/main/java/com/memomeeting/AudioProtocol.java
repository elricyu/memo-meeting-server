package com.memomeeting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;

/** MM01: 4-byte magic + uint32 sequence + uint64 PCM byte offset, little-endian. */
public final class AudioProtocol {
    public static final int HEADER_BYTES=16, MAX_PCM_BYTES=32000;
    public record Frame(long sequence,long offset,byte[] pcm) {}
    private AudioProtocol() {}
    public static Map<String,Object> specification() {
        return Map.of("format","pcm_s16le","sampleRate",16000,"channels",1,"bitsPerSample",16,
            "frameHeaderBytes",HEADER_BYTES,"recommendedChunkMs",100,"maxChunkBytes",MAX_PCM_BYTES);
    }
    public static Frame decode(byte[] frame) {
        if(frame.length<HEADER_BYTES+2 || frame.length>HEADER_BYTES+MAX_PCM_BYTES)
            throw new IllegalArgumentException("音频帧长度无效");
        var buffer=ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        if(buffer.get()!= 'M' || buffer.get()!= 'M' || buffer.get()!= '0' || buffer.get()!= '1')
            throw new IllegalArgumentException("音频帧魔数无效，应为 MM01");
        long sequence=Integer.toUnsignedLong(buffer.getInt());long offset=buffer.getLong();
        if(offset<0 || (offset%2)!=0 || ((frame.length-HEADER_BYTES)%2)!=0)
            throw new IllegalArgumentException("音频偏移与 PCM 样本必须按 2 字节对齐");
        return new Frame(sequence,offset,Arrays.copyOfRange(frame,HEADER_BYTES,frame.length));
    }
}
