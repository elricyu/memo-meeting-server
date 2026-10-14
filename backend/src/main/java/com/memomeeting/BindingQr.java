package com.memomeeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;

@Component
public class BindingQr {
    public record Payload(int v,String requestId,String token) {}
    private final ObjectMapper json;
    public BindingQr(ObjectMapper json){this.json=json;}
    public Payload decode(byte[] jpeg) {
        if(jpeg.length<4 || jpeg.length>2097152 || (jpeg[0]&255)!=255 || (jpeg[1]&255)!=216)bad("扫码图片应为不超过2MB的JPEG");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())bad("无法读取扫码图片");
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<16 || height<16 || (long)width*height>12000000)bad("扫码图片尺寸无效");
                var image=reader.read(0);var binary=new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
                var result=new MultiFormatReader().decode(binary,Map.of(DecodeHintType.POSSIBLE_FORMATS,List.of(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER,true));
                if(result.getText().length()>400)bad("这不是会议系统绑定二维码");
                var node=json.readTree(result.getText());
                String id=node.path("requestId").asText(),token=node.path("token").asText();
                if(node.path("v").asInt()!=1 || !id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}") || !token.matches("[a-f0-9]{64}"))bad("这不是有效的会议系统绑定二维码");
                return new Payload(1,id,token);
            } finally {reader.dispose();}
        } catch(ResponseStatusException e){throw e;}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未识别到绑定二维码，请正对屏幕、调整距离后重试");}
    }
    static void bad(String reason){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);}
}
