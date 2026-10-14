package com.memomeeting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.concurrent.Semaphore;
@RestController
@RequestMapping("/api/v1/devices")
public class WebBindingApi {
    public record Claim(@NotNull @Valid DeviceApi.Pair device,@NotNull @Pattern(regexp="[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}") String requestId,@NotNull @Pattern(regexp="[a-f0-9]{64}") String token) {}
    public record Scan(@NotNull @Valid DeviceApi.Pair device,@NotBlank @Size(max=2796204) String jpegBase64) {}
    public record Recover(@NotNull @Pattern(regexp="[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}") String clientRequestId,@NotNull @Pattern(regexp="[a-f0-9]{64}") String deviceToken) {}
    public record Check(@NotBlank @Size(max=128) String clientId,@NotNull @Pattern(regexp="(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}") String mac,@Pattern(regexp="[a-f0-9]{64}") String deviceToken) {}
    private final WebBindings bindings;private final RateLimits limits;private final BindingQr qr;
    private final Semaphore decoding=new Semaphore(1);
    public WebBindingApi(WebBindings bindings,RateLimits limits,BindingQr qr){this.bindings=bindings;this.limits=limits;this.qr=qr;}
    @PostMapping("/web-bindings") public Object create(){long user=Access.user().userId();limits.check("web-bind:"+user,10);return bindings.create(user);}
    @GetMapping("/web-bindings/{id}") public Object read(@PathVariable String id){return bindings.read(id,Access.user().userId());}
    @PostMapping("/web-bindings/{id}/confirm") public Object confirm(@PathVariable String id){return bindings.confirm(id,Access.user().userId());}
    @PostMapping("/web-bindings/{id}/cancel") public Object cancel(@PathVariable String id){bindings.cancel(id,Access.user().userId());return Map.of("cancelled",true);}
    @PostMapping("/binding-claims") public Object claim(@Valid @RequestBody Claim input,HttpServletRequest request){limits.check("qr-claim:"+request.getRemoteAddr(),20);return bindings.claim(input.requestId(),input.token(),input.device());}
    @PostMapping("/binding-claims/recover") public Object recover(@Valid @RequestBody Recover input,HttpServletRequest request){limits.check("qr-recover:"+request.getRemoteAddr(),30);return bindings.recover(input.clientRequestId(),input.deviceToken());}
    @PostMapping("/binding-claims/check") public Object check(@Valid @RequestBody Check input,HttpServletRequest request){limits.check("qr-check:"+request.getRemoteAddr(),30);return bindings.check(input.clientId().trim(),input.mac().toUpperCase(Locale.ROOT),input.deviceToken());}
    @PostMapping("/binding-claims/scan") public Object scan(@Valid @RequestBody Scan input,HttpServletRequest request){
        limits.check("qr-scan:"+request.getRemoteAddr(),20);
        if(!decoding.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"扫码服务繁忙，请稍后重试");
        try {byte[] jpeg;try{jpeg=Base64.getDecoder().decode(input.jpegBase64());}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"扫码图片格式无效");}var payload=qr.decode(jpeg);return bindings.claim(payload.requestId(),payload.token(),input.device());}
        finally {decoding.release();}
    }
}
