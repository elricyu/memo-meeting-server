package com.memomeeting;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(Map.of("message",Objects.requireNonNullElse(e.getReason(),"请求失败")));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> invalid(IllegalStateException e){return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> badRequest(Exception e){return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("message","缺少必要请求头或 JSON 格式错误"));}
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void viewerDisconnected(Exception e){ /* The peer closed SSE; its response cannot be written. */ }
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<?> duplicate(Exception e){return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(Map.of("message","请求冲突：此设备、MAC 或请求 ID 已被使用"));}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unexpected(Exception e){org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("api_failed cause={}",e.getClass().getSimpleName());return ResponseEntity.status(500).contentType(MediaType.APPLICATION_JSON).body(Map.of("message","服务处理失败，请查看服务日志"));}
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.web.method.annotation.HandlerMethodValidationException.class,jakarta.validation.ConstraintViolationException.class}) public ResponseEntity<?> validation(Exception e){return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("message","输入参数无效，请检查字段格式和长度"));}
}
